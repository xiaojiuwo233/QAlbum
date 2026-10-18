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
        assertEquals((1 until AppDatabase.VERSION).toList(), allMigrations.map { it.startVersion })
        assertEquals((2..AppDatabase.VERSION).toList(), allMigrations.map { it.endVersion })
    }

    @Test
    fun migrate25To26_addsFreedTimeToCompressions() {
        val name = "migration-25-26"
        helper.createDatabase(name, 25).use { db ->
            db.execSQL(
                """INSERT INTO original_backups(image_id, path, backup_path, original_size, original_mtime, original_width, original_height,
                    compressed_size, compressed_width, compressed_height, created_at, expires_at, state, quality, original_mime)
                    VALUES(1, '/a', NULL, 100, 1, 10, 10, 40, 10, 10, 5, 6, 'ready', 80, NULL)""",
            )
        }
        helper.runMigrationsAndValidate(name, 26, true, AppDatabase.MIGRATION_25_26).use { db ->
            // 以前的压缩记录不知道何时腾出来：累计照算，「今日」不算
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM original_backups WHERE freed_at IS NULL"))
        }
    }

    @Test
    fun migrate24To25_addsVideos() {
        val name = "migration-24-25"
        helper.createDatabase(name, 24).close()
        helper.runMigrationsAndValidate(name, 25, true, AppDatabase.MIGRATION_24_25).use { db ->
            db.execSQL("INSERT INTO videos(path, source, size, mtime, date_bucket, duration_ms, width, height, seen_at) VALUES('/v/a.mp4', 'chat', 10, 1, 1, -1, 0, 0, 1)")
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM videos WHERE duration_ms < 0"))
        }
    }

    @Test
    fun migrate23To24_addsSentPreviewChecks() {
        val name = "migration-23-24"
        helper.createDatabase(name, 23).close()
        helper.runMigrationsAndValidate(name, 24, true, AppDatabase.MIGRATION_23_24).use { db ->
            // 核对结果：是发送图片缓存的记着同一次发送的原图，不是的为空
            db.execSQL("INSERT INTO qq_preview_checks(path, size, raw_path, checked_at) VALUES('/chatimg/a', 10, '/chatraw/b', 1)")
            db.execSQL("INSERT INTO qq_preview_checks(path, size, raw_path, checked_at) VALUES('/chatimg/c', 20, NULL, 1)")
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM qq_preview_checks WHERE raw_path IS NOT NULL"))
        }
    }

    @Test
    fun migrate22To23_addsReadStateAutoArchiveAndReadMarks() {
        val name = "migration-22-23"
        helper.createDatabase(name, 22).use { db ->
            db.execSQL("INSERT INTO qq_source_accounts(account_key, display_name, imported_at, picture_count) VALUES('a', '账号', 1, 1)")
            db.execSQL(
                """INSERT INTO qq_origins(id, account_key, conversation_key, conversation_name, chat_type, sender_key, sender_name,
                    member_name, message_time, md5) VALUES('o1', 'a', 'g', '群', 2, 's', '人', '', 1000, '${"0".repeat(32)}')""",
            )
            db.execSQL("INSERT OR IGNORE INTO qq_source_cache(id, dirty, image_count, image_bytes, thumb_count, thumb_bytes) VALUES(1, 0, 0, 0, 0, 0)")
        }
        helper.runMigrationsAndValidate(name, 23, true, AppDatabase.MIGRATION_22_23).use { db ->
            // 以前读到的来源：没记读取时间、图片属性未知，下次读取时补上
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM qq_origins WHERE seen_at = 0 AND pic_type = 0 AND pic_sub_type = -1 AND sent_original = 0"))
            assertEquals(0L, scalarLong(db, "SELECT complete_read_at FROM qq_source_cache WHERE id = 1"))
            db.execSQL("INSERT INTO qq_auto_archive(conversation_key, sender_key, label, created_at) VALUES('g', '', '群', 1)")
            db.execSQL("INSERT INTO qq_read_marks(account_key, table_name, max_rowid, full_read_at) VALUES('a', 'group_msg_table', 10, 1)")
            // 清除来源索引（删账号）时读取水位一起删，自动归档名单保留
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM qq_source_accounts")
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM qq_read_marks"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM qq_auto_archive"))
        }
    }

    @Test
    fun migrate21To22_addsOriginalFormatToCompressions() {
        val name = "migration-21-22"
        helper.createDatabase(name, 21).use { db ->
            db.execSQL(
                """INSERT INTO original_backups(id, image_id, path, backup_path, original_size, original_mtime, original_width, original_height,
                    compressed_size, compressed_width, compressed_height, created_at, expires_at, state, quality)
                VALUES(1, 7, '/chatpic/chatraw/a', NULL, 4000, 1000, 4000, 3000, 1000, 2560, 1920, 1000, 2000, 'ready', 80)""",
            )
        }
        helper.runMigrationsAndValidate(name, 22, true, AppDatabase.MIGRATION_21_22).use { db ->
            // 以前的记录没有格式（都是 JPEG）
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM original_backups WHERE original_mime IS NULL AND quality = 80"))
        }
    }

    @Test
    fun migrate20To21_recordsQualityOfExistingCompressions() {
        val name = "migration-20-21"
        helper.createDatabase(name, 20).use { db ->
            db.execSQL(
                """INSERT INTO original_backups(id, image_id, path, backup_path, original_size, original_mtime, original_width, original_height,
                    compressed_size, compressed_width, compressed_height, created_at, expires_at, state)
                VALUES(1, 7, '/chatpic/chatraw/a', '/backup/a', 4000, 1000, 4000, 3000, 1000, 2560, 1920, 1000, 2000, 'ready')""",
            )
        }
        helper.runMigrationsAndValidate(name, 21, true, AppDatabase.MIGRATION_20_21).use { db ->
            // 以前的压缩固定用 85
            assertEquals(85L, scalarLong(db, "SELECT quality FROM original_backups WHERE id = 1"))
            assertEquals("/backup/a", scalarString(db, "SELECT backup_path FROM original_backups WHERE id = 1"))
        }
    }

    @Test
    fun migrate19To20_turnsKeepIntoArchiveDropsAnalysisAndRebuildsImages() {
        val name = "migration-19-20"
        helper.createDatabase(name, 19).use { db ->
            // v19 的触发器不在导出的 schema 里（由迁移 / 建库回调创建），这里补上
            AppDatabase.createQqSourceTriggers(db)
            db.execSQL(QqSourceStatsSql.ENSURE_CACHE_ROW)
            QqSourceStatsSql.createTriggers(db)
            ImageVariantSql.createTriggers(db)
            db.execSQL("""CREATE TRIGGER invalidate_changed_image_analysis AFTER UPDATE OF size, mtime ON images
                BEGIN DELETE FROM ocr_text WHERE image_id = NEW.id; END""")

            // 家族 7：原图标了「保留」，同一张图的预览图一起转为归档
            insertImage(db, 1, "/chatpic/chatraw/kept", "active", 0, 1, 1000)
            insertImage(db, 2, "/chatpic/chatimg/kept", "active", 0, null, 1000)
            db.execSQL("UPDATE images SET variant_group = 7 WHERE id IN (1, 2)")
            insertImage(db, 3, "/chatpic/chatimg/plain", "active", 0, null, 1000)
            // 已在回收站里的「保留」不转
            insertImage(db, 4, "qalbum-internal://quarantined/4", "quarantined", 0, 1, 1000)
            // 删掉的行占用过的 id 不能在换表后被重新分配
            insertImage(db, 50, "/chatpic/chatimg/gone", "active", 0, null, 1000)
            db.execSQL("DELETE FROM images WHERE id = 50")

            db.execSQL("INSERT INTO qq_source_accounts VALUES('a','账号',1,1)")
            db.execSQL("INSERT INTO qq_origins VALUES('o','a','g','群名',2,'s','昵称','群名片',1000,'md5')")
            db.execSQL("INSERT INTO image_qq_origins VALUES(2,'o')")
            db.execSQL("""INSERT INTO quarantine(id,image_id,original_path,quarantine_path,thumbnail_path,reason,ocr_summary,size,quarantined_at,expires_at,state,group_id,file_count,source_stamp)
                VALUES(1,4,'/chatpic/chatimg/old','/quarantine/4',NULL,'test',NULL,100,1000,999999,'ready',NULL,1,NULL)""")
            db.execSQL("INSERT INTO tags(id,name,type,color,auto_protect,auto_delete) VALUES(1,'t','user',NULL,0,0)")
            db.execSQL("INSERT INTO image_tags VALUES(1,1,1.0,'user')")
            db.execSQL("INSERT INTO ocr_text VALUES(1,'text',0.9,0,0,0,0,0,1000)")
            db.execSQL("INSERT INTO ocr_fts(rowid,text) VALUES(1,'text')")
            db.execSQL("INSERT INTO ai_analysis VALUES(1,'d','t','m',1000)")
            db.execSQL("INSERT INTO image_embeddings VALUES(1,'m',1,X'0000803F',1000)")
            db.execSQL("INSERT INTO dedup_exclusions VALUES(1,1)")
            db.execSQL("INSERT INTO cleanup_batches VALUES(1,'s',1,100,'reviewing',1,NULL)")
            db.execSQL("INSERT INTO cleanup_candidates VALUES(1,1,1,'r','low',0.1,NULL)")
        }
        helper.runMigrationsAndValidate(name, 20, true, AppDatabase.MIGRATION_19_20).use { db ->
            assertEquals(listOf(1L, 1L, 0L, 0L), (1..4).map { scalarLong(db, "SELECT is_protected FROM images WHERE id = $it") })
            assertEquals(4L, scalarLong(db, "SELECT COUNT(*) FROM images WHERE optimized = 0"))
            assertEquals("/chatpic/chatimg/plain", scalarString(db, "SELECT path FROM images WHERE id = 3"))
            assertEquals(2L, scalarLong(db, "SELECT COUNT(*) FROM images WHERE variant_group = 7"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM image_qq_origins WHERE image_id = 2 AND origin_id = 'o'"))
            assertEquals(4L, scalarLong(db, "SELECT image_id FROM quarantine WHERE id = 1"))

            listOf("ocr_text", "tags", "image_tags", "ai_analysis", "image_embeddings",
                "dedup_exclusions", "cleanup_batches", "cleanup_candidates").forEach { assertFalse(it, tableExists(db, it)) }
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'ocr_fts%'"))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'invalidate_changed_image_analysis'"))
            listOf("link_qq_image_origin_0", "link_qq_image_origin_1", "qq_stats_image_update", "qq_stats_image_insert",
                "qq_stats_image_path", "qq_stats_image_delete", "image_variants_insert", "image_variants_update",
                "image_variants_delete", "image_plain_bytes_insert", "image_plain_bytes_update", "dirty_qq_links_delete",
            ).forEach { trigger ->
                assertEquals(trigger, 1L, scalarLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='$trigger'"))
            }
            db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }

            // 换表后触发器照常工作：新图关联来源、补自身大小，id 接着旧序号
            db.execSQL("INSERT INTO qq_origin_paths VALUES('/chatpic/chatimg/new','o')")
            db.execSQL(
                """INSERT INTO images(path, filename, parent_dir, source_type, cache_key, size, mtime, first_seen, last_seen,
                    mime, width, height, is_animated, status, is_protected)
                VALUES('/chatpic/chatimg/new', 'Cache_new', '/chatpic', 'chatimg', 'new', 300, 1000, 1000, 1000,
                    'image/jpeg', 0, 0, 0, 'active', 0)""",
            )
            assertEquals(51L, scalarLong(db, "SELECT id FROM images WHERE path = '/chatpic/chatimg/new'"))
            assertEquals(300L, scalarLong(db, "SELECT variant_bytes FROM images WHERE id = 51"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM image_qq_origins WHERE image_id = 51"))
            // 家族重算：原图变大后仍是代表图，合计两个版本
            db.execSQL("UPDATE images SET size = 500 WHERE id = 1")
            assertEquals(600L, scalarLong(db, "SELECT variant_bytes FROM images WHERE id = 1"))
            assertEquals(1L, scalarLong(db, "SELECT variant_hidden FROM images WHERE id = 2"))
            // 外键仍指向新表：删图级联删掉来源关联
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("DELETE FROM images WHERE id = 2")
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM image_qq_origins WHERE image_id = 2"))
        }
    }

    @Test
    fun migrate18To19_indexesDisplaySizeAndKeepsPlainRowsInSync() {
        val name = "migration-18-19"
        helper.createDatabase(name, 18).use { db ->
            insertImage(db, 1, "/chatpic/chatimg/plain", "active", 0, null, 1000)
            insertImage(db, 2, "/chatpic/chatimg/plain-2", "active", 0, null, 1000)
        }
        helper.runMigrationsAndValidate(name, 19, true, AppDatabase.MIGRATION_18_19).use { db ->
            // 已有行不在迁移里重写（由启动后的回填补齐）
            assertEquals(0L, scalarLong(db, "SELECT variant_bytes FROM images WHERE id = 1"))
            listOf("image_variants_insert", "image_variants_update", "image_variants_delete",
                "image_plain_bytes_insert", "image_plain_bytes_update").forEach { trigger ->
                assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='$trigger'"))
            }
            // 之后的变化：不属于家族的图，合并显示时的大小跟着自身大小
            db.execSQL("UPDATE images SET size = 300 WHERE id = 2")
            assertEquals(300L, scalarLong(db, "SELECT variant_bytes FROM images WHERE id = 2"))
            insertImage(db, 3, "/chatpic/chatimg/plain-3", "active", 0, null, 1000)
            assertEquals(100L, scalarLong(db, "SELECT variant_bytes FROM images WHERE id = 3"))
            db.execSQL(ImageVariantSql.plainBytesRange, arrayOf<Any>(0, Long.MAX_VALUE))
            assertEquals(100L, scalarLong(db, "SELECT variant_bytes FROM images WHERE id = 1"))
        }
    }

    @Test
    fun migrate17To18_addsPictureFamilyColumnsWithoutTouchingRows() {
        val name = "migration-17-18"
        helper.createDatabase(name, 17).use { db ->
            insertImage(db, 1, "/chatpic/chatraw/a", "active", 0, null, 1000)
            db.execSQL("UPDATE images SET variant_group = 42 WHERE id = 1")
        }
        helper.runMigrationsAndValidate(name, 18, true, AppDatabase.MIGRATION_17_18).use { db ->
            // 历史变体分组清空，新列为默认值；家族由启动后的回填建立
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM images WHERE variant_group IS NULL AND variant_hidden = 0 AND variant_count = 0 AND variant_bytes = 0"))
            listOf("image_variants_insert", "image_variants_update", "image_variants_delete").forEach { trigger ->
                assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='$trigger'"))
            }
        }
    }

    @Test
    fun migrate16To17_rebuildsSourceStatsTablesAndPausesTriggersUntilRebuilt() {
        val name = "migration-16-17"
        helper.createDatabase(name, 16).use { db ->
            insertImage(db, 1, "/chatpic/chatimg/linked", "active", 0, null, 1000)
            db.execSQL("INSERT INTO qq_source_accounts VALUES('a','账号',1,1)")
            db.execSQL("INSERT INTO qq_origins VALUES('o','a','g','群名',2,'s','昵称','群名片',1000,'md5')")
            db.execSQL("INSERT INTO qq_origin_paths VALUES('/chatpic/chatimg/linked','o')")
            db.execSQL("INSERT INTO image_qq_origins VALUES(1,'o')")
            db.execSQL("INSERT INTO qq_source_facets(kind,`key`,name,search_text,account_key,image_count,chat_type,total_bytes,latest_time) VALUES(0,'g','群名','群名','a',1,2,100,1000)")
            db.execSQL("INSERT INTO qq_source_cache(id,dirty,linked_count,linked_bytes) VALUES(1,0,1,100)")
            // v16 的整体重建触发器（迁移需要把它们换掉）
            AppDatabase.createQqSourceCacheTriggers(db)
            AppDatabase.createQqSourceSizeTrigger(db)
        }
        helper.runMigrationsAndValidate(name, 17, true, AppDatabase.MIGRATION_16_17).use { db ->
            // 来源本身原样保留；统计是缓存，清空后标脏等待分段重建
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM qq_origins"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM image_qq_origins"))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM qq_source_facets"))
            assertEquals(1L, scalarLong(db, "SELECT dirty FROM qq_source_cache WHERE id=1"))
            QqSourceStatsSql.legacyTriggers.forEach { trigger ->
                assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='$trigger'"))
            }
            listOf("qq_stats_image_update", "qq_stats_image_insert", "qq_stats_image_path", "qq_stats_image_delete").forEach { trigger ->
                assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='$trigger'"))
            }
            // 标脏期间触发器不做增量：删图不改统计，也不再触发整体重建
            db.execSQL("UPDATE images SET status='quarantine_pending' WHERE id=1")
            assertEquals(0L, scalarLong(db, "SELECT image_count FROM qq_source_cache WHERE id=1"))
            assertEquals(1L, scalarLong(db, "SELECT dirty FROM qq_source_cache WHERE id=1"))
        }
    }

    @Test
    fun migrate15To16_keepsOldQuarantineRecordsAndAddsGroupJournalColumns() {
        val name="migration-15-16"
        helper.createDatabase(name,15).use { db ->
            insertImage(db,1,"qalbum-internal://quarantine/1","quarantined",0,null,1000)
            db.execSQL("""INSERT INTO quarantine(id,image_id,original_path,quarantine_path,thumbnail_path,reason,ocr_summary,size,quarantined_at,expires_at,state)
                VALUES(1,1,'/chatpic/chatraw/retained','/quarantine/retained',NULL,'test',NULL,100,1000,999999,'ready')""")
        }
        helper.runMigrationsAndValidate(name,16,true,AppDatabase.MIGRATION_15_16).use { db ->
            assertEquals(1L,scalarLong(db,"SELECT COUNT(*) FROM quarantine WHERE group_id IS NULL AND source_stamp IS NULL AND file_count=1 AND state='ready'"))
            assertEquals("/quarantine/retained",scalarString(db,"SELECT quarantine_path FROM quarantine WHERE id=1"))
            assertEquals(1L,scalarLong(db,"SELECT COUNT(*) FROM images"))
        }
    }

    @Test
    fun migrate14To15_addsByteTotalsAndInvalidatesCacheWhenLinkedSizeChanges() {
        val name = "migration-14-15"
        helper.createDatabase(name, 14).use { db ->
            insertImage(db, 1, "/chatpic/chatraw/linked", "active", 0, null, 1000)
            insertImage(db, 2, "/chatpic/chatraw/unlinked", "active", 0, null, 1000)
            db.execSQL("INSERT INTO qq_source_accounts VALUES('a','账号',1,1)")
            db.execSQL("INSERT INTO qq_origins VALUES('o','a','g','群名',2,'s','昵称','群名片',1000,'md5')")
            db.execSQL("INSERT INTO image_qq_origins VALUES(1,'o')")
            db.execSQL("INSERT INTO qq_source_facets(kind,`key`,name,search_text,account_key,image_count) VALUES(0,'g','群名','群名','a',1)")
            db.execSQL("INSERT INTO qq_source_cache(id,dirty,linked_count) VALUES(1,0,1)")
        }
        helper.runMigrationsAndValidate(name, 15, true, AppDatabase.MIGRATION_14_15).use { db ->
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM image_qq_origins"))
            assertEquals(1L, scalarLong(db, "SELECT image_count FROM qq_source_facets WHERE `key`='g'"))
            assertEquals(0L, scalarLong(db, "SELECT total_bytes FROM qq_source_facets WHERE `key`='g'"))
            // 统计口径变化：迁移后缓存必须标脏，由来源页按新口径重建
            assertEquals(1L, scalarLong(db, "SELECT dirty FROM qq_source_cache WHERE id=1"))
            db.execSQL("UPDATE qq_source_cache SET dirty=0")
            db.execSQL("UPDATE images SET size=200 WHERE id=2")
            assertEquals(0L, scalarLong(db, "SELECT dirty FROM qq_source_cache WHERE id=1"))
            db.execSQL("UPDATE images SET size=300 WHERE id=1")
            assertEquals(1L, scalarLong(db, "SELECT dirty FROM qq_source_cache WHERE id=1"))
        }
    }

    @Test
    fun migrate13To14_preservesSourcesAndInvalidatesOnlyChangedFacetCounts() {
        val name = "migration-13-14"
        helper.createDatabase(name, 13).use { db ->
            insertImage(db, 1, "/chatpic/chatraw/retained", "active", 1, 1, 1000)
            db.execSQL("INSERT INTO qq_source_accounts VALUES('a','账号',1,1)")
            db.execSQL("INSERT INTO qq_origins VALUES('o','a','g','群名',2,'s','昵称','群名片',1000,'md5')")
            db.execSQL("INSERT INTO image_qq_origins VALUES(1,'o')")
        }
        helper.runMigrationsAndValidate(name, 14, true, AppDatabase.MIGRATION_13_14).use { db ->
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM image_qq_origins"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM images WHERE user_keep=1 AND is_protected=1"))
            assertEquals(1L, scalarLong(db, "SELECT dirty FROM qq_source_cache WHERE id=1"))
            db.execSQL("UPDATE qq_source_cache SET dirty=0")
            db.execSQL("UPDATE images SET last_seen=2000 WHERE id=1")
            assertEquals(0L, scalarLong(db, "SELECT dirty FROM qq_source_cache WHERE id=1"))
            db.execSQL("UPDATE images SET status='quarantined' WHERE id=1")
            assertEquals(1L, scalarLong(db, "SELECT dirty FROM qq_source_cache WHERE id=1"))
        }
    }

    @Test
    fun migrate12To13_preservesImagesAndLinksLaterScansWithoutReplacingSources() {
        val name = "migration-12-13"
        helper.createDatabase(name, 12).use { db ->
            insertImage(db, 1, "/chatpic/chatraw/one", "active", 1, 1, 1000)
        }
        helper.runMigrationsAndValidate(name, 13, true, AppDatabase.MIGRATION_12_13).use { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM images WHERE id=1 AND is_protected=1 AND user_keep=1"))
            db.execSQL("INSERT INTO qq_source_accounts(account_key,display_name,imported_at,picture_count) VALUES ('a','账号',1,1)")
            db.execSQL("""INSERT INTO qq_origins(id,account_key,conversation_key,conversation_name,chat_type,sender_key,sender_name,member_name,message_time,md5)
                VALUES ('o','a','g','完整群名',2,'s','完整发送人名','群名片',1000,'00000000000000000000000000000000')""")
            db.execSQL("INSERT INTO qq_origin_paths(path,origin_id) VALUES ('/chatpic/chatimg/later','o')")
            insertImage(db, 2, "/chatpic/chatimg/later", "active", 0, null, 2000)
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM image_qq_origins WHERE image_id=2 AND origin_id='o'"))
            db.execSQL("UPDATE images SET path='qalbum-internal://quarantined/2', status='quarantined' WHERE id=2")
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM image_qq_origins WHERE image_id=2"))
            db.execSQL("DELETE FROM qq_source_accounts")
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM qq_origins"))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM qq_origin_paths"))
            assertEquals(2L, scalarLong(db, "SELECT COUNT(*) FROM images"))
        }
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
        private val allMigrations = AppDatabase.allMigrations()

        private const val TEST_DB_V1 = "migration-1-11"
        private const val TEST_DB = "migration-9-11"
        private const val SAME_PATH = "/chatpic/Cache_same"
        private const val REUSABLE_PATH = "/chatpic/Cache_reused"
    }
}
