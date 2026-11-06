package org.kavo.uploader.upload

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The classes that have to be uploaded again because they read a constant whose value changed.
 *
 * The Java IDE support resolves the reads of a constant exactly: qualified names, a static import, a name
 * inherited through a type hierarchy and a use inside the declaring class itself all end up in the result.
 * Kotlin sources are not searched: their usages are not part of the Java index this scan goes through.
 */
object ConstantUsageScanner {
    private val INLINED_TYPES =
        setOf("boolean", "byte", "short", "char", "int", "long", "float", "double", "String")

    /**
     * Source files that read one of the changed constants, mapped to the change that explains why they are
     * needed.
     *
     * The search goes through the IDE's index and needs a read lock, so the caller runs it in a background
     * task with the index available.
     */
    fun affectedFiles(project: Project, changes: Map<Path, ConstantFileChange>): Map<Path, String> {
        val affected = linkedMapOf<Path, String>()
        val psiManager = PsiManager.getInstance(project)
        changes.forEach { entry ->
            val sourcePath = entry.key
            val change = entry.value
            if (change.constantNames.isEmpty()) return@forEach
            val virtualFile =
                LocalFileSystem.getInstance().findFileByPath(sourcePath.toString()) ?: return@forEach
            val psiFile = psiManager.findFile(virtualFile) ?: return@forEach
            val fields = fieldsOf(psiFile)
            val scope = usageScope(project, virtualFile)
            change.constantNames.forEach { name ->
                val match = fields.firstOrNull { isCompileTimeConstant(it.field, it.inInterface) && it.field.name == name }
                    ?: return@forEach
                referencesOf(match.field, scope).forEach { reference ->
                    val userFile = reference.getElement().containingFile ?: return@forEach

                    val userVirtualFile = userFile.virtualFile ?: return@forEach
                    // The declaring file itself is in the change list already, its own bytecode comes from there.
                    if (userVirtualFile.path == virtualFile.path) return@forEach
                    if (!userVirtualFile.name.endsWith(".java")) return@forEach
                    if (!isUnderProject(project, userVirtualFile)) return@forEach
                    val userPath = Paths.get(userVirtualFile.path)
                    if (affected[userPath] == null) affected[userPath] = change.summary
                }
            }
        }
        return affected
    }

    /**
     * The files the usage search reads.
     *
     * `projectScope` covers the imported source roots only, which is nothing at all for a project opened as a
     * plain folder - there the whole index is searched and the results that are not under the project are
     * dropped afterwards.
     */
    private fun usageScope(project: Project, declaringFile: VirtualFile): GlobalSearchScope {
        val scope = GlobalSearchScope.projectScope(project)
        return if (scope.contains(declaringFile)) scope else GlobalSearchScope.everythingScope(project)
    }

    /** Whether a searched file belongs to this project's own sources rather than to a library or a jar. */
    private fun isUnderProject(project: Project, file: VirtualFile): Boolean {
        val base = (project.basePath ?: return false).replace('\\', '/').trimEnd('/')
        val path = file.path
        // A jar entry that holds a copy of the class is not a file this plugin can upload.
        if (path.contains("!")) return false
        return path == base || path.startsWith("$base/")
    }

    private data class FieldInClass(val field: PsiField, val inInterface: Boolean)

    private fun fieldsOf(psiFile: PsiFile): List<FieldInClass> {
        val classes = (psiFile as? PsiClassOwner)?.getClasses()?.toList() ?: return emptyList()
        return classes.flatMap { fieldsOfClasses(it) }
    }

    private fun fieldsOfClasses(psiClass: PsiClass): List<FieldInClass> {
        val inInterface = psiClass.isInterface()
        val fields = psiClass.getFields().map { field -> FieldInClass(field, inInterface) }.toList()
        val inner = psiClass.getInnerClasses().flatMap { fieldsOfClasses(it) }.toList()
        return fields + inner
    }

    /**
     * A `static final` primitive or `String` field that has a value, which is what the compiler inlines.
     * Interface fields are `static final` implicitly, so their modifiers are not asked for.
     */
    private fun isCompileTimeConstant(field: PsiField, inInterface: Boolean): Boolean {
        val type = field.getType().toString().substringAfterLast('.')
        if (!INLINED_TYPES.contains(type)) return false
        if (field.getInitializer() == null) return false
        if (inInterface) return true
        return field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)
    }

    /**
     * Every read of the field inside the project. Searching with the access scope ignored keeps a private
     * constant's own class in the result, which the caller filters out, and hides nothing else.
     */
    private fun referencesOf(field: PsiField, scope: GlobalSearchScope): List<PsiReference> =
        ReferencesSearch.search(field, scope, true).findAll().toList()
}
