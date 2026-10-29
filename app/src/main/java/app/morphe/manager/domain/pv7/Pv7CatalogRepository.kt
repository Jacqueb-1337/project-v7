package app.morphe.manager.domain.pv7

/** Restrict custom catalogs to a GitHub repository, never arbitrary download URLs. */
object Pv7CatalogRepository {
    const val OFFICIAL = "Jacqueb-1337/project-v7"
    private val pattern = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    fun validate(value: String): String {
        val repo = value.trim()
        require(repo.length <= 120 && pattern.matches(repo) &&
            repo.split('/').none { it == "." || it == ".." }) {
            "Catalog must be a GitHub owner/repository pair"
        }
        return repo
    }

    fun baseUrl(repository: String): String =
        "https://raw.githubusercontent.com/" + validate(repository) + "/main/catalog/"
}
