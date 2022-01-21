package org.openrewrite

import org.openrewrite.config.CategoryDescriptor
import java.lang.Runnable
import org.openrewrite.config.RecipeDescriptor
import java.lang.RuntimeException
import java.nio.file.Files
import java.io.IOException
import java.io.BufferedWriter
import java.nio.file.StandardOpenOption
import java.lang.StringBuilder
import org.openrewrite.config.Environment
import org.openrewrite.internal.StringUtils
import org.openrewrite.internal.StringUtils.isNullOrEmpty
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.collections.LinkedHashMap
import kotlin.io.path.toPath
import kotlin.jvm.JvmStatic
import kotlin.system.exitProcess

@Command(
    name = "rewrite-recipe-markdown-generator",
    mixinStandardHelpOptions = true,
    description = ["Generates documentation for OpenRewrite recipes in markdown format"],
    version = ["1.0.0-SNAPSHOT"]
)
class RecipeMarkdownGenerator : Runnable {
    @Parameters(index = "0", description = ["Destination directory for generated recipe markdown"])
    lateinit var destinationDirectoryName: String

    @Parameters(index = "1", defaultValue = "", description = ["A ';' delineated list of coordinates to search for recipes. " +
            "Each entry in the list must be of format groupId:artifactId:version:path where 'path' is a file path to the jar"])
    lateinit var recipeSources: String

    @Parameters(index = "2", defaultValue = "", description = ["A ';' delineated list of jars that provide the full " +
            "transitive dependency list for the recipeSources"])
    lateinit var recipeClasspath: String

    @Parameters(index = "3", defaultValue = "latest.release", description = ["The version of the Rewrite Gradle Plugin to display in relevant samples"])
    lateinit var gradlePluginVersion: String

    @Parameters(index = "4", defaultValue = "", description = ["The version of the Rewrite Maven Plugin to display in relevant samples"])
    lateinit var mavenPluginVersion: String

    override fun run() {
        val outputPath = Paths.get(destinationDirectoryName)
        val recipesPath = outputPath.resolve("reference/recipes")
        try {
            Files.createDirectories(recipesPath)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        val env: Environment
        val recipeOrigins: Map<URI, RecipeOrigin>
        if (recipeSources.isNotEmpty() && recipeClasspath.isNotEmpty()) {
            recipeOrigins = RecipeOrigin.parse(recipeSources)

            val classloader = recipeClasspath.split(";")
                    .map(Paths::get)
                    .map(Path::toUri)
                    .map(URI::toURL)
                    .toTypedArray()
                    .let { URLClassLoader(it) }

            val envBuilder = Environment.builder()
            for(recipeOrigin in recipeOrigins) {
                envBuilder.scanJar(recipeOrigin.key.toPath(), classloader)
            }
            env = envBuilder.build()
        } else {
            recipeOrigins = emptyMap()
            env = Environment.builder()
                    .scanRuntimeClasspath()
                    .build()
        }
        val recipeDescriptors: List<RecipeDescriptor> = env.listRecipeDescriptors()
                .filterNot { it.name.startsWith("org.openrewrite.text") } // These are test utilities only
        val categoryDescriptors = ArrayList(env.listCategoryDescriptors())
        for (recipeDescriptor in recipeDescriptors) {
            var origin: RecipeOrigin?
            var rawUri = recipeDescriptor.source.toString()
            val exclamationIndex = rawUri.indexOf('!')
            if (exclamationIndex == -1) {
                origin = recipeOrigins[recipeDescriptor.source]
            } else {
                // The recipe origin includes the path to the recipe within a jar
                // Such URIs will look something like: jar:file:/path/to/the/recipes.jar!META-INF/rewrite/some-declarative.yml
                // Strip the "jar:" prefix and the part of the URI pointing inside the jar
                rawUri = rawUri.substring(0, exclamationIndex)
                rawUri = rawUri.substring(4)
                val jarOnlyUri = URI.create(rawUri)
                origin = recipeOrigins[jarOnlyUri]
            }
            requireNotNull(origin) { "Could not find GAV coordinates of recipe " + recipeDescriptor.name + " from " + recipeDescriptor.source }
            writeRecipe(recipeDescriptor, recipesPath, origin, gradlePluginVersion, mavenPluginVersion)
        }
        val categories = Category.fromDescriptors(recipeDescriptors, categoryDescriptors)

        // Write SUMMARY_snippet.md
        val summarySnippetPath = outputPath.resolve("SUMMARY_snippet.md")
        Files.newBufferedWriter(summarySnippetPath, StandardOpenOption.CREATE).useAndApply {
            for(category in categories) {
                write(category.summarySnippet(0))
            }
        }

        // Write the README.md for each category
        for(category in categories) {
            val categoryIndexPath = outputPath.resolve("reference/recipes/")
            category.writeCategoryIndex(categoryIndexPath)
        }
    }

    data class Category(
            val simpleName: String,
            val path: String,
            val descriptor: CategoryDescriptor?,
            val recipes: List<RecipeDescriptor>,
            val subcategories: List<Category>
    ) {
        companion object {
            private data class CategoryBuilder(
                    val path: String? = null,
                    val recipes: MutableList<RecipeDescriptor> = mutableListOf(),
                    val subcategories: LinkedHashMap<String, CategoryBuilder> = LinkedHashMap()
            ) {
                fun build(categoryDescriptors: List<CategoryDescriptor>): Category {
                    val simpleName = path!!.substring(path.lastIndexOf('/') + 1)
                    val descriptor = findCategoryDescriptor(path, categoryDescriptors)
                    // Do not consider backticks while sorting, they're formatting.
                    val finalizedSubcategories = subcategories.values.asSequence()
                            .map { it.build(categoryDescriptors) }
                            .sortedBy { it.displayName.replace("`", "") }
                            .toList()
                    return Category(
                            simpleName,
                            path,
                            descriptor,
                            recipes.sortedBy { it.displayName.replace("`", "") },
                            finalizedSubcategories)
                }
            }

            fun fromDescriptors(recipes: Iterable<RecipeDescriptor>, descriptors: List<CategoryDescriptor>): List<Category> {
                val result = LinkedHashMap<String, CategoryBuilder>()
                for(recipe in recipes) {
                    result.putRecipe(getRecipeCategory(recipe), recipe)
                }

                return result.mapValues { it.value.build(descriptors) }
                        .values
                        .toList()
            }

            private fun MutableMap<String, CategoryBuilder>.putRecipe(recipeCategory: String?, recipe: RecipeDescriptor) {
                if(recipeCategory == null) {
                    return
                }
                val pathSegments = recipeCategory.split("/")
                var category = this
                for(i in pathSegments.indices) {
                    val pathSegment = pathSegments[i]
                    val pathToCurrent = pathSegments.subList(0, i + 1).joinToString("/")
                    if(!category.containsKey(pathSegment)) {
                        category[pathSegment] = CategoryBuilder(path = pathToCurrent)
                    }
                    if(i == pathSegments.size - 1) {
                        category[pathSegment]!!.recipes.add(recipe)
                    }
                    category = category[pathSegment]!!.subcategories
                }
            }
        }

        val displayName: String =
                if (descriptor == null) {
                    StringUtils.capitalize(simpleName)
                } else {
                    descriptor.displayName
                }

        /**
         * Produce the snippet for this category to be fitted into Gitbook's SUMMARY.md, which provides the index
         * that makes markdown documents accessible through gitbook's interface
         */
        fun summarySnippet(indentationDepth: Int): String {
            val indentBuilder = StringBuilder("  ")
            for(i in 0 until indentationDepth) {
                indentBuilder.append("  ")
            }
            val indent = indentBuilder.toString()
            val result = StringBuilder()

            result.appendLine("$indent* [$displayName](reference/recipes/$path/README.md)")
            for(recipe in recipes) {
                // Section headings will display backticks, rather than rendering as code. Omit them so it doesn't look terrible
                result.appendLine("$indent  * [${recipe.displayName.replace("`", "")}](${getRecipeRelativePath(recipe)}.md)")
            }
            for(category in subcategories) {
                result.append(category.summarySnippet(indentationDepth + 1))
            }
            return result.toString()
        }

        /**
         * Produce the contents of the README.md file for this category.
         */
        private fun categoryIndex(): String {
            return StringBuilder().apply {
                appendLine("# $displayName")
                // While the description is not _supposed_ to be nullable it has happened before
                @Suppress("SENSELESS_COMPARISON")
                if(descriptor != null && descriptor.description != null) {
                    appendLine()
                    appendLine("_${descriptor.description}_")
                }
                appendLine()
                if(recipes.isNotEmpty()) {
                    appendLine("## Recipes")
                    appendLine()
                    for(recipe in recipes) {
                        val recipeSimpleName = recipe.name.substring(recipe.name.lastIndexOf('.') + 1).lowercase()
                        // Anything except a relative link ending in .md will be mangled.
                        // If you touch this line double check that it works when imported into gitbook
                        appendLine("* [${recipe.displayName}](${recipeSimpleName}.md)")
                    }
                    appendLine()
                }
                if(subcategories.isNotEmpty()) {
                    appendLine("## Subcategories")
                    appendLine()
                    for(subcategory in subcategories) {
                        appendLine("* [${subcategory.displayName}](/reference/recipes/${subcategory.path})")
                    }
                    appendLine()
                }
            }.toString()
        }

        fun writeCategoryIndex(outputRoot: Path) {
            if(path.isBlank()) {
                // Don't yet support "core" recipes that aren't in any language category
                return;
            }
            val outputPath = outputRoot.resolve("$path/README.md")
            Files.newBufferedWriter(outputPath, StandardOpenOption.CREATE).useAndApply {
                writeln(categoryIndex())
            }
            for(subcategory in subcategories) {
                subcategory.writeCategoryIndex(outputRoot)
            }
        }
    }

    private fun writeRecipe(recipeDescriptor: RecipeDescriptor, outputPath: Path, origin: RecipeOrigin, gradlePluginVersion: String, mavenPluginVersion: String) {
        val recipeMarkdownPath = getRecipePath(outputPath, recipeDescriptor)
        Files.createDirectories(recipeMarkdownPath.parent)
        Files.newBufferedWriter(recipeMarkdownPath, StandardOpenOption.CREATE).useAndApply {
            write("""
                # ${recipeDescriptor.displayName}
                
                ** ${recipeDescriptor.name.replace("_".toRegex(), "\\\\_")}**
                
            """.trimIndent())
            if (!isNullOrEmpty(recipeDescriptor.description)) {
                writeln("_" + recipeDescriptor.description + "_")
            }
            newLine()
            if (recipeDescriptor.tags.isNotEmpty()) {
                writeln("### Tags")
                newLine()
                for (tag in recipeDescriptor.tags) {
                    writeln("* $tag")
                }
                newLine()
            }
            writeln("""
                ## Source
                
                [Github](${origin.githubUrl()}), [Issue Tracker](${origin.issueTrackerUrl()}), [Maven Central](https://search.maven.org/artifact/${origin.groupId}/${origin.artifactId}/${origin.version}/jar)
                
                * groupId: ${origin.groupId}
                * artifactId: ${origin.artifactId}
                * version: ${origin.version}
                
            """.trimIndent())

            if (recipeDescriptor.options.isNotEmpty()) {
                writeln("""
                    ## Options
                    
                    | Type | Name | Description |
                    | -- | -- | -- |
                """.trimIndent())
                for (option in recipeDescriptor.options) {
                    var description = if(option.description == null) {
                        ""
                    } else {
                        option.description
                    }
                    description = if(option.isRequired) {
                        description
                    } else {
                        "*Optional*. $description"
                    }
                    // This should preserve casing and plurality
                    description = description.replace("method patterns?".toRegex(RegexOption.IGNORE_CASE)) { match ->
                        "[${match.value}](/reference/method-patterns)"
                    }
                    writeln("""
                        | `${option.type}` | ${option.name} | $description |
                    """.trimIndent())
                }
                newLine()
            }

            newLine()
            writeln("## Usage")
            newLine()
            val requiresConfiguration = recipeDescriptor.options.any { it.isRequired }
            val requiresDependency = !origin.isFromCoreLibrary()
            if (requiresConfiguration) {
                val exampleRecipeName = "com.yourorg." + recipeDescriptor.name.substring(recipeDescriptor.name.lastIndexOf('.') + 1) + "Example"
                write("This recipe has required configuration parameters. ")
                write("Recipes with required configuration parameters cannot be activated directly. ")
                write("To activate this recipe you must create a new recipe which fills in the required parameters. ")
                write("In your rewrite.yml create a new recipe with a unique name. ")
                write("For example: `$exampleRecipeName`.")
                newLine()
                writeln("Here's how you can define and customize such a recipe within your rewrite.yml:")
                write("""
                    
                    {% code title="rewrite.yml" %}
                    ```yaml
                    ---
                    type: specs.openrewrite.org/v1beta/recipe
                    name: $exampleRecipeName
                    displayName: ${recipeDescriptor.displayName} example
                    recipeList:
                      - ${recipeDescriptor.name}:
                    
                """.trimIndent())
                for(option in recipeDescriptor.options) {
                    writeln("      ${option.name}: ${option.example}")
                }
                writeln("```")
                writeln("{% endcode %}")
                newLine()
                if(requiresDependency) {
                    writeln("""
                        Now that `$exampleRecipeName` has been defined activate it and take a dependency on ${origin.groupId}:${origin.artifactId}:${origin.version} in your build file:
                        
                        {% tabs %}
                        {% tab title="Gradle" %}
                        {% code title="build.gradle" %}
                        ```groovy
                        plugins {
                            id("org.openrewrite.rewrite") version("$gradlePluginVersion")
                        }
    
                        rewrite {
                            activeRecipe("$exampleRecipeName")
                        }
    
                        repositories {
                            mavenCentral()
                        }
                        
                        dependencies {
                            rewrite("${origin.groupId}:${origin.artifactId}:${origin.version}")
                        }
                        ```
                        {% endcode %}
                        {% endtab %}
    
                        {% tab title="Maven" %}
                        {% code title="pom.xml" %}
                        ```markup
                        <project>
                          <build>
                            <plugins>
                              <plugin>
                                <groupId>org.openrewrite.maven</groupId>
                                <artifactId>rewrite-maven-plugin</artifactId>
                                <version>$mavenPluginVersion</version>
                                <configuration>
                                  <activeRecipes>
                                    <recipe>$exampleRecipeName</recipe>
                                  </activeRecipes>
                                </configuration>
                                <dependencies>
                                  <dependency>
                                    <groupId>${origin.groupId}</groupId>
                                    <artifactId>${origin.artifactId}</artifactId>
                                    <version>${origin.version}</version>
                                  </dependency>
                                </dependencies>
                              </plugin>
                            </plugins>
                          </build>
                        </project>
                        ```
                        {% endcode %}
                        {% endtab %}
                        {% endtabs %}
                        
                """.trimIndent())
                } else {
                    writeln("""
                        
                        Now that `$exampleRecipeName` has been defined activate it in your build file:
                        
                        {% tabs %}
                        {% tab title="Gradle" %}
                        {% code title="build.gradle" %}
                        ```groovy
                        plugins {
                            id("org.openrewrite.rewrite") version("$gradlePluginVersion")
                        }
    
                        rewrite {
                            activeRecipe("$exampleRecipeName")
                        }
    
                        repositories {
                            mavenCentral()
                        }

                        ```
                        {% endcode %}
                        {% endtab %}
    
                        {% tab title="Maven" %}
                        {% code title="pom.xml" %}
                        ```markup
                        <project>
                          <build>
                            <plugins>
                              <plugin>
                                <groupId>org.openrewrite.maven</groupId>
                                <artifactId>rewrite-maven-plugin</artifactId>
                                <version>$mavenPluginVersion</version>
                                <configuration>
                                  <activeRecipes>
                                    <recipe>$exampleRecipeName</recipe>
                                  </activeRecipes>
                                </configuration>
                              </plugin>
                            </plugins>
                          </build>
                        </project>
                        ```
                        {% endcode %}
                        {% endtab %}
                        {% endtabs %}
                        
                    """.trimIndent())
                }
                writeln("Recipes can also be activated directly from the commandline by adding the argument `-DactiveRecipe=${exampleRecipeName}`")
            } else {
                if(origin.isFromCoreLibrary()) {
                    writeln("This recipe has no required configuration parameters and comes from a rewrite core library. " +
                            "It can be activated directly without adding any dependencies.")
                    writeln("""
                        
                        {% tabs %}
                        {% tab title="Gradle" %}
                        {% code title="build.gradle" %}
                        ```groovy
                        plugins {
                            id("org.openrewrite.rewrite") version("$gradlePluginVersion")
                        }
    
                        rewrite {
                            activeRecipe("${recipeDescriptor.name}")
                        }
    
                        repositories {
                            mavenCentral()
                        }

                        ```
                        {% endcode %}
                        {% endtab %}
    
                        {% tab title="Maven" %}
                        {% code title="pom.xml" %}
                        ```markup
                        <project>
                          <build>
                            <plugins>
                              <plugin>
                                <groupId>org.openrewrite.maven</groupId>
                                <artifactId>rewrite-maven-plugin</artifactId>
                                <version>$mavenPluginVersion</version>
                                <configuration>
                                  <activeRecipes>
                                    <recipe>${recipeDescriptor.name}</recipe>
                                  </activeRecipes>
                                </configuration>
                              </plugin>
                            </plugins>
                          </build>
                        </project>
                        ```
                        {% endcode %}
                        {% endtab %}
                        {% endtabs %}
                        
                    """.trimIndent())
                } else {
                    writeln("This recipe has no required configuration options and can be activated directly after " +
                            "taking a dependency on ${origin.groupId}:${origin.artifactId}:${origin.version} in your build file:")
                    writeln("""
                        
                        {% tabs %}
                        {% tab title="Gradle" %}
                        {% code title="build.gradle" %}
                        ```groovy
                        plugins {
                            id("org.openrewrite.rewrite") version("$gradlePluginVersion")
                        }
    
                        rewrite {
                            activeRecipe("${recipeDescriptor.name}")
                        }
    
                        repositories {
                            mavenCentral()
                        }
                        
                        dependencies {
                            rewrite("${origin.groupId}:${origin.artifactId}:${origin.version}")
                        }
                        ```
                        {% endcode %}
                        {% endtab %}
    
                        {% tab title="Maven" %}
                        {% code title="pom.xml" %}
                        ```markup
                        <project>
                          <build>
                            <plugins>
                              <plugin>
                                <groupId>org.openrewrite.maven</groupId>
                                <artifactId>rewrite-maven-plugin</artifactId>
                                <version>$mavenPluginVersion</version>
                                <configuration>
                                  <activeRecipes>
                                    <recipe>${recipeDescriptor.name}</recipe>
                                  </activeRecipes>
                                </configuration>
                                <dependencies>
                                  <dependency>
                                    <groupId>${origin.groupId}</groupId>
                                    <artifactId>${origin.artifactId}</artifactId>
                                    <version>${origin.version}</version>
                                  </dependency>
                                </dependencies>
                              </plugin>
                            </plugins>
                          </build>
                        </project>
                        ```
                        {% endcode %}
                        {% endtab %}
                        {% endtabs %}
                        
                    """.trimIndent())
                }
                writeln("Recipes can also be activated directly from the command line by adding the argument `-DactiveRecipe=${recipeDescriptor.name}`")
            }

            if (recipeDescriptor.recipeList.isNotEmpty()) {
                writeln("""
                    
                    ## Definition
                    
                    {% tabs %}
                    {% tab title="Recipe List" %}
                """.trimIndent())
                val recipeDepth = getRecipePath(recipeDescriptor).chars().filter { ch: Int -> ch == '/'.code }.count()
                val pathToRecipesBuilder = StringBuilder()
                for (i in 0 until recipeDepth) {
                    pathToRecipesBuilder.append("../")
                }
                val pathToRecipes = pathToRecipesBuilder.toString()
                for (recipe in recipeDescriptor.recipeList) {
                    writeln("* [" + recipe.displayName + "](" + pathToRecipes + getRecipePath(recipe) + ".md)")
                    if (recipe.options.isNotEmpty()) {
                        for (option in recipe.options) {
                            if (option.value != null) {
                                writeln("  * " + option.name + ": `" + printValue(option.value!!) + "`")
                            }
                        }
                    }
                }
                newLine()
                writeln("""
                    {% endtab %}

                    {% tab title="Yaml Recipe List" %}
                    ```yaml
                """.trimIndent())
                writeln(recipeDescriptor.asYaml())
                writeln("""
                    ```
                    {% endtab %}
                    {% endtabs %}
                """.trimIndent())
            }
        }
    }

    companion object {
        private fun printValue(value: Any): String =
                if (value is Array<*>) {
                    value.contentDeepToString()
                } else {
                    value.toString()
                }

        /**
         * Call Closable.use() together with apply() to avoid adding two levels of indentation
         */
        fun BufferedWriter.useAndApply(withFun: BufferedWriter.()->Unit): Unit = use { it.apply(withFun) }

        fun BufferedWriter.writeln(text: String) {
            write(text)
            newLine()
        }

        private fun getRecipeCategory(recipe: RecipeDescriptor): String {
            val recipePath = getRecipePath(recipe)
            val slashIndex = recipePath.lastIndexOf("/")
            return if(slashIndex == -1) {
                "";
            } else {
                recipePath.substring(0, slashIndex)
            }
        }

        private fun getRecipePath(recipe: RecipeDescriptor): String =
                if (recipe.name.startsWith("org.openrewrite")) {
                    recipe.name.substring(16).replace("\\.".toRegex(), "/").lowercase(Locale.getDefault())
                } else {
                    throw RuntimeException("Recipe package unrecognized: ${recipe.name}")
                }

        private fun getRecipePath(recipesPath: Path, recipeDescriptor: RecipeDescriptor) =
                recipesPath.resolve(getRecipePath(recipeDescriptor) + ".md")

        private fun getRecipeRelativePath(recipe: RecipeDescriptor): String =
                "/reference/recipes/" + getRecipePath(recipe)

        private fun findCategoryDescriptor(categoryPathFragment: String, categoryDescriptors: Iterable<CategoryDescriptor>): CategoryDescriptor? {
            val categoryPackage = "org.openrewrite.${categoryPathFragment.replace('/', '.')}"
            return categoryDescriptors.find { descriptor -> descriptor.packageName == categoryPackage}
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val exitCode = CommandLine(RecipeMarkdownGenerator()).execute(*args)
            exitProcess(exitCode)
        }
    }
}