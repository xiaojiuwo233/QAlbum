package com.xjw.qalbum.service.ai

import com.xjw.qalbum.data.db.entity.AiAnalysisEntity
import org.junit.Assert.*
import org.junit.Test

class SemanticDocumentTest {
    @Test
    fun noContentProducesNoEmbeddingInput() {
        assertEquals("", SemanticDocument.build(null, null, emptyList()))
        assertEquals("", SemanticDocument.build(null, null, listOf("用户保留")))
    }

    @Test
    fun visualDescriptionTagsAndOcrAllContributeToSearch() {
        val analysis = AiAnalysisEntity(1, "a red bicycle beside a lake", "photo", "vision", 1)
        val text = SemanticDocument.build("receipt 12.30", analysis, listOf("bicycle", "bicycle", "landscape"))
        assertTrue(text.contains(analysis.description))
        assertTrue(text.contains("receipt 12.30"))
        assertEquals(1, Regex("bicycle").findAll(text.substringAfter("photo\n")).count())
    }

    @Test
    fun modelIdentitySeparatesBothModelsAndServers() {
        assertNotEquals(SemanticDocument.modelKey("http://localhost:8000", "one"), SemanticDocument.modelKey("http://localhost:8000", "two"))
        assertNotEquals(SemanticDocument.modelKey("http://localhost:8000", "one"), SemanticDocument.modelKey("http://localhost:8001", "one"))
        assertEquals(SemanticDocument.modelKey("http://localhost:8000/", "one"), SemanticDocument.modelKey("http://localhost:8000", "one"))
    }

    @Test
    fun malformedAndEmptyAiResponsesAreRejected() {
        assertNull(AiTagResponseParser.parse("{}"))
        assertNull(AiTagResponseParser.parse("[]"))
        assertNull(AiTagResponseParser.parse("{\"content_tags\":\"invalid\",\"description\":\"x\"}"))
    }

    @Test
    fun fencedResponseNormalizesTagsWithoutCreatingUserKeep() {
        val parsed = AiTagResponseParser.parse("```json\n{\"content_tags\":[\" receipt \",\"receipt\",123,\"用户保留\"],\"risk_tags\":[],\"description\":\"a receipt\"}\n```")!!
        assertEquals(listOf("receipt"), parsed.contentTags)
        assertEquals("a receipt", parsed.description)
    }
}
