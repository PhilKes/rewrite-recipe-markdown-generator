package org.openrewrite

import java.util.TreeSet

data class MarkdownRecipeDescriptor (
    val name: String,
    val description: String,
    val options: TreeSet<RecipeOption>
): Comparable<MarkdownRecipeDescriptor> {
    override fun compareTo(other: MarkdownRecipeDescriptor): Int {
        if (this.name != other.name) return this.name.compareTo(other.name)
        if (this.description != other.description) return this.description.compareTo(other.description)
        if (this.options != other.options) return -1
        return 0
    }
}