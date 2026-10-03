package com.xjw.qalbum.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationEdgesCoverEveryPublishedVersion() {
        assertEquals((1..11).toList(), allMigrations.map { it.startVersion })
        assertEquals((2..12).toList(), allMigrations.map { it.endVersion })
    }

    @Test
    fun migrate1To11_preservesLegacyImageOcrAndTags() {
        helper.createDatabase(TEST_DB_V1, 1).use { db ->
            insertImage(db, 100, "/chatpic/Cache_legacy", "active", isProtected = 1, userKeep = 1, lastSeen = 2_000)
            db.execSQL("INSERT INTO tags(id, name, type, color, auto_protect, auto_delete) VALUES (200, '旧标签', 'user', NULL, 0, 0)")
            db.execSQL("INSERT INTO image_tags(image_id, tag_id, confidence, source) VALUES (100, 200, 1.0, 'user')")
            db.execSQL(
                "INSERT INTO ocr_text(image_id, text, confidence, has_phone, has_amount, has_address, " +
                    "has_id_number, has_order_no, updated_at) VALUES (100, '历史 OCR', 0.7, 0, 0, 0, 0, 0, 1111)"
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DB_V1,
            11,
            true,
            *allMigrations.toTypedArray(),
        ).use { db ->
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM images WHERE id = 100 AND is_protected = 1 AND user_keep = 1"))
            assertEquals("历史 OCR", scalarString(db, "SELECT text FROM ocr_text WHERE image_id = 100"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM image_tags WHERE image_id = 100 AND tag_id = 200"))
            assertTrue(tableExists(db, "ocr_fts"))
            assertTrue(tableExists(db, "image_embeddings"))
            assertTrue(tableExists(db, "dir_snapshots"))
            assertTrue(tableExists(db, "dedup_exclusions"))
        }
    }

    @Test
    fun migrate9To11_preservesDuplicateStateAndReleasesTerminalPaths() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            insertImage(db, 1, SAME_PATH, "active", isProtected = 0, userKeep = 1, lastSeen = 2_000)
            insertImage(db, 2, SAME_PATH, "active", isProtected = 1, userKeep = null, lastSeen = 3_000)
            insertImage(db, 3, SAME_PATH, "quarantined", isProtected = 0, userKeep = null, lastSeen = 1_000)
            insertImage(db, 4, REUSABLE_PATH, "deleted", isProtected = 0, userKeep = null, lastSeen = 1_000)

            db.execSQL("INSERT INTO tags(id, name, type, color, auto_protect, auto_delete) VALUES (10, '票据', 'user', NULL, 0, 0)")
            db.execSQL("INSERT INTO image_tags(image_id, tag_id, confidence, source) VALUES (1, 10, 0.5, 'ai')")
            db.execSQL("INSERT INTO image_tags(image_id, tag_id, confidence, source) VALUES (2, 10, 0.9, 'user')")
            db.execSQL(
                "INSERT INTO ocr_text(image_id, text, confidence, has_phone, has_amount, has_address, " +
                    "has_id_number, has_order_no, updated_at) VALUES (1, '保留的 OCR', 0.8, 0, 1, 0, 0, 0, 1234)"
            )
            db.execSQL("INSERT INTO image_embeddings(image_id, model, dim, vector, updated_at) VALUES (1, 'm', 1, X'0000803F', 2345)")
            db.execSQL("INSERT INTO cleanup_batches(id, strategy, total_count, total_size, status, created_at, executed_at) VALUES (20, 'test', 1, 100, 'reviewing', 1, NULL)")
            db.execSQL("INSERT INTO cleanup_candidates(id, batch_id, image_id, reason, risk_level, score, user_decision) VALUES (30, 20, 1, 'test', 'low', 0.1, NULL)")
            db.execSQL("INSERT INTO dedup_exclusions(cohort_id, image_id) VALUES (40, 1)")
            db.execSQL(
                "INSERT INTO quarantine(id, image_id, original_path, quarantine_path, thumbnail_path, reason, " +
                    "ocr_summary, size, quarantined_at, expires_at) VALUES " +
                    "(50, 3, '$SAME_PATH', '/quarantine/3', NULL, 'test', NULL, 100, 1, 999999)"
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            11,
            true,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
        ).use { db ->
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM images WHERE path = '$SAME_PATH'"))
            assertEquals(2L, scalarLong(db, "SELECT id FROM images WHERE path = '$SAME_PATH'"))
            assertEquals(1L, scalarLong(db, "SELECT is_protected FROM images WHERE id = 2"))
            assertEquals(1L, scalarLong(db, "SELECT user_keep FROM images WHERE id = 2"))
            assertEquals("保留的 OCR", scalarString(db, "SELECT text FROM ocr_text WHERE image_id = 2"))
            assertEquals(0.9, scalarDouble(db, "SELECT confidence FROM image_tags WHERE image_id = 2 AND tag_id = 10"), 0.0001)
            assertEquals("user", scalarString(db, "SELECT source FROM image_tags WHERE image_id = 2 AND tag_id = 10"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM image_embeddings WHERE image_id = 2"))
            assertEquals(2L, scalarLong(db, "SELECT image_id FROM cleanup_candidates WHERE id = 30"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM dedup_exclusions WHERE cohort_id = 40 AND image_id = 2"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM quarantine WHERE image_id = 3 AND state = 'ready'"))
            assertTrue(scalarString(db, "SELECT path FROM images WHERE id = 3").startsWith("qalbum-internal://quarantined/"))
            assertTrue(scalarString(db, "SELECT path FROM images WHERE id = 4").startsWith("qalbum-internal://deleted/"))
            assertFalse(tableExists(db, "matched_messages"))
            assertFalse(tableExists(db, "user_actions"))

            insertImage(db, 5, REUSABLE_PATH, "active", isProtected = 0, userKeep = null, lastSeen = 5_000)
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM images WHERE path = '$REUSABLE_PATH'"))
        }
    }

    @Test
    fun migrate11To12_preservesUserDataAndInvalidatesOnlyChangedContent() {
        val name = "migration-11-12"
        helper.createDatabase(name, 11).use { db ->
            insertImage(db, 1, "/chatpic/Cache_revision", "active", 1, 1, 1000)
            db.execSQL("INSERT INTO tags(id, name, type, color, auto_protect, auto_delete) VALUES (1, 'manual', 'user', NULL, 0, 0), (2, 'auto', 'ai', NULL, 0, 0)")
            db.execSQL("INSERT INTO image_tags(image_id, tag_id, confidence, source) VALUES (1, 1, 1.0, 'user'), (1, 2, 1.0, 'ai')")
            db.execSQL("INSERT INTO ocr_text(image_id, text, confidence, has_phone, has_amount, has_address, has_id_number, has_order_no, updated_at) VALUES (1, 'old content', 0.9, 0, 1, 0, 0, 0, 1000)")
            db.execSQL("INSERT INTO ocr_fts(rowid, text) VALUES (1, 'old content')")
            db.execSQL("INSERT INTO image_embeddings(image_id, model, dim, vector, updated_at) VALUES (1, 'legacy', 1, X'0000803F', 1000)")
        }
        helper.runMigrationsAndValidate(name, 12, true, AppDatabase.MIGRATION_11_12).use { db ->
            assertTrue(tableExists(db, "ai_analysis"))
            assertEquals("old content", scalarString(db, "SELECT text FROM ocr_text WHERE image_id = 1"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM image_embeddings"))
            db.execSQL("INSERT INTO ai_analysis(image_id, description, info_type, model, updated_at) VALUES (1, 'receipt', 'document', 'vision', 1000)")
            db.execSQL("UPDATE images SET last_seen = 2000 WHERE id = 1")
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM ai_analysis"))
            db.execSQL("UPDATE images SET size = 200, mtime = 2000 WHERE id = 1")
            for (table in listOf("ocr_text", "ocr_fts", "ai_analysis", "image_embeddings")) {
                assertEquals("stale $table", 0L, scalarLong(db, "SELECT COUNT(*) FROM $table"))
            }
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM image_tags WHERE source = 'user'"))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM image_tags WHERE source = 'ai'"))
            assertEquals(1L, scalarLong(db, "SELECT is_protected FROM images WHERE id = 1"))
            assertEquals(1L, scalarLong(db, "SELECT user_keep FROM images WHERE id = 1"))
        }
    }

    private fun insertImage(
        db: SupportSQLiteDatabase,
        id: Long,
        path: String,
        status: String,
        isProtected: Int,
        userKeep: Int?,
        lastSeen: Long,
    ) {
        db.execSQL(
            """
            INSERT INTO images(
                id, path, filename, parent_dir, source_type, cache_key, is_variant_primary,
                size, mtime, first_seen, last_seen, mime, width, height, is_animated,
                is_corrupt, is_blank, hash_done, ocr_done, ai_done, embedding_done,
                info_score, delete_score, is_duplicate_best, status, is_protected, user_keep
            ) VALUES (
                $id, '$path', 'Cache_$id', '/chatpic', 'chatimg', 'cache-$id', 0,
                100, 1000, 1000, $lastSeen, 'image/jpeg', 100, 100, 0,
                0, 0, 0, 0, 0, 0,
                0.1, 0.2, 0, '$status', $isProtected, ${userKeep ?: "NULL"}
            )
            """.trimIndent()
        )
    }

    private fun scalarLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun scalarDouble(db: SupportSQLiteDatabase, sql: String): Double =
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getDouble(0)
        }

    private fun scalarString(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        scalarLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$name'") > 0

    companion object {
        private val allMigrations = listOf(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
        )

        private const val TEST_DB_V1 = "migration-1-11"
        private const val TEST_DB = "migration-9-11"
        private const val SAME_PATH = "/chatpic/Cache_same"
        private const val REUSABLE_PATH = "/chatpic/Cache_reused"
    }
}
