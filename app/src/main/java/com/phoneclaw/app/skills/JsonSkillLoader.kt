package com.phoneclaw.app.skills

import android.content.res.AssetManager
import java.io.File

private const val DEFAULT_SKILLS_DIRECTORY = "skills"

class JsonSkillLoader private constructor(
    private val listDocuments: (String) -> List<String>,
    private val readDocument: (String) -> String,
) {
    fun loadSkillPackages(directory: String = DEFAULT_SKILLS_DIRECTORY): List<SkillPackageDefinition> {
        val documentNames = listDocuments(directory)
            .filter { it.endsWith(".json", ignoreCase = true) }
            .sorted()

        return documentNames.map { documentName ->
            val documentPath = if (directory.isBlank()) documentName else "$directory/$documentName"
            parseSkillPackageDocument(
                jsonText = readDocument(documentPath),
                source = documentPath,
            )
        }
    }

    fun loadRegisteredActions(directory: String = DEFAULT_SKILLS_DIRECTORY): List<RegisteredSkillAction> {
        return loadSkillPackages(directory).flatMap { it.toRegisteredActions() }
    }

    companion object {
        fun fromAssets(assetManager: AssetManager): JsonSkillLoader {
            return JsonSkillLoader(
                listDocuments = { directory -> assetManager.list(directory)?.toList().orEmpty() },
                readDocument = { path ->
                    assetManager.open(path).bufferedReader().use { reader ->
                        reader.readText()
                    }
                },
            )
        }

        fun fromDirectory(rootDir: File): JsonSkillLoader {
            return JsonSkillLoader(
                listDocuments = { directory ->
                    val targetDir = if (directory.isBlank()) rootDir else File(rootDir, directory)
                    targetDir.listFiles()
                        ?.filter { it.isFile }
                        ?.map { it.name }
                        .orEmpty()
                },
                readDocument = { path ->
                    File(rootDir, path.replace('/', File.separatorChar)).readText()
                },
            )
        }
    }
}
