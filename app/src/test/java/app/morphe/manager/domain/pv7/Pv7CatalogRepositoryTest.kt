package app.morphe.manager.domain.pv7

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Pv7CatalogRepositoryTest {
    @Test fun validatesGitHubRepo() {
        assertEquals("Brendan250/project-v7", Pv7CatalogRepository.validate(" Brendan250/project-v7 "))
        assertEquals(
            "https://raw.githubusercontent.com/Brendan250/project-v7/main/catalog/",
            Pv7CatalogRepository.baseUrl("Brendan250/project-v7")
        )
    }
    @Test fun rejectsExternalHostsAndTraversal() {
        for (value in listOf("https://evil.example/catalog", "../secret", "foo/bar/../other",
            "foo//bar", "foo/bar?query=1", "")) {
            assertThrows(IllegalArgumentException::class.java) {
                Pv7CatalogRepository.validate(value)
            }
        }
    }
}
