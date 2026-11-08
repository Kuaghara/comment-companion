package dev.commentcompanion.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.Collections

/**
 * 打开文档的改动追踪：只记录「哪些文件变了」和「上次看到/自己写过的 stamp」。
 *
 * 不解析内容、不碰 PSI——真正的审查由 ScanCycle 在空闲时做。
 */
@Service(Service.Level.PROJECT)
class GoFileTracker(private val project: Project) : Disposable {

    private val dirty: MutableSet<VirtualFile> = Collections.synchronizedSet(LinkedHashSet())
    private val lastSeen: MutableMap<VirtualFile, Long> = Collections.synchronizedMap(HashMap())
    private val ownStamps: MutableMap<VirtualFile, Long> = Collections.synchronizedMap(HashMap())
    private val attachedDocs = Collections.synchronizedSet(HashSet<Any>())

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            val document = event.document
            val file = FileDocumentManager.getInstance().getFile(document) ?: return
            onDocumentChanged(file, document.modificationStamp)
        }
    }

    private val editorListener = object : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) {
            attach(event.editor.document)
        }

        override fun editorReleased(event: EditorFactoryEvent) {
            // 文档关闭后再按需重新 attach 即可；这里不做额外处理，避免误删正在用的监听。
        }
    }

    fun start() {
        ApplicationManager.getApplication().invokeLater {
            for (file in com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles) {
                for (editor in com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getAllEditors(file)) {
                    val textEditor = editor as? com.intellij.openapi.fileEditor.TextEditor ?: continue
                    attach(textEditor.editor.document)
                }
            }
        }
        EditorFactory.getInstance().addEditorFactoryListener(editorListener, this)
    }

    private fun attach(document: com.intellij.openapi.editor.Document) {
        if (attachedDocs.add(document)) {
            document.addDocumentListener(documentListener, this)
        }
        // 首次见到的打开文档：即使还没有编辑，也要审查一次（文件里可能已有 ai： 注释）
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (file.isValid && file.extension == "go" && !lastSeen.containsKey(file)) {
            lastSeen[file] = document.modificationStamp
            dirty += file
        }
    }

    private fun onDocumentChanged(file: VirtualFile, stamp: Long) {
        if (!file.isValid || file.extension != "go") return
        val own = ownStamps[file]
        if (own != null && stamp == own) return // 自己写的，不触发
        if (lastSeen[file] == stamp) return
        lastSeen[file] = stamp
        dirty += file
    }

    /** 取出并清空 dirty 集合（空闲扫描时调用）。 */
    fun consumeDirty(): List<VirtualFile> {
        val snapshot = synchronized(dirty) {
            val list = dirty.toList()
            dirty.clear()
            list
        }
        return snapshot.filter { it.isValid }
    }

    /** 落笔后登记自己的 stamp，避免下一 tick 把它当成用户改动。 */
    fun markOwnWrite(file: VirtualFile, stamp: Long) {
        ownStamps[file] = stamp
        lastSeen[file] = stamp
    }

    fun pendingDirtyCount(): Int = dirty.size

    override fun dispose() {
        dirty.clear()
        lastSeen.clear()
        ownStamps.clear()
        attachedDocs.clear()
    }
}
