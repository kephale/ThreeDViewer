/*-
 * #%L
 * Scenery-backed 3D visualization package for ImageJ.
 * %%
 * Copyright (C) 2016 - 2020 SciView developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package sc.iview.ui

import com.intellij.ui.components.JBPanel
import graphics.scenery.Camera
import graphics.scenery.Node
import graphics.scenery.Scene
import net.miginfocom.swing.MigLayout
import org.joml.Quaternionf
import org.joml.Vector3f
import org.scijava.Context
import org.scijava.command.CommandService
import org.scijava.event.EventHandler
import org.scijava.log.LogService
import org.scijava.module.Module
import org.scijava.module.ModuleException
import org.scijava.module.ModuleItem
import org.scijava.module.ModuleService
import org.scijava.plugin.Parameter
import org.scijava.plugin.PluginService
import org.scijava.service.Service
import org.scijava.ui.swing.widget.SwingInputPanel
import org.scijava.util.DebugUtils
import org.scijava.widget.UIComponent
import sc.iview.SciView
import sc.iview.commands.edit.Properties
import sc.iview.commands.help.Help
import sc.iview.event.NodeActivatedEvent
import sc.iview.event.NodeAddedEvent
import sc.iview.event.NodeChangedEvent
import sc.iview.event.NodeRemovedEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.swing.*
import javax.swing.event.TreeSelectionEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Interactive UI for visualizing and editing the scene graph.
 *
 * @author Curtis Rueden
 * @author Ulrik Guenther
 */
class SwingNodePropertyEditor(private val sciView: SciView) : UIComponent<JPanel> {
    @Parameter
    private lateinit var pluginService: PluginService

    @Parameter
    private lateinit var moduleService: ModuleService

    @Parameter
    private lateinit var commandService: CommandService

    @Parameter
    private lateinit var log: LogService

    private var panel: JPanel? = null
    private lateinit var treeModel: DefaultTreeModel

    lateinit var tree: JTree
        private set
    lateinit var props: JBPanel<*>

    fun getProps(): JPanel {
        return props
    }

    /** Creates and displays a window containing the scene editor.  */
    fun show() {
        val frame = JFrame("Node Properties")
        frame.setLocation(200, 200)
        frame.contentPane = component
        // FIXME: Why doesn't the frame disappear when closed?
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.setSize(600, 400)
        frame.isVisible = true
    }

    override fun getComponent(): JPanel {
        if (panel == null) {
            initPanel()
        }
        return panel!!
    }

    override fun getComponentType(): Class<JPanel> {
        return JPanel::class.java
    }

    @EventHandler
    private fun onEvent(evt: NodeAddedEvent) {
        val node = evt.node ?: return
        log.trace("Node added: $node");
        rebuildTree()
    }

    @EventHandler
    private fun onEvent(evt: NodeRemovedEvent) {
        val node = evt.node ?: return
        log.trace("Node removed: $node");
        rebuildTree()
    }

    @EventHandler
    private fun onEvent(evt: NodeChangedEvent) {
        val node = evt.node ?: return
        if (node != sciView.activeNode) {
            updateProperties(sciView.activeNode)
        }
    }

    @EventHandler
    private fun onEvent(evt: NodeActivatedEvent) {
        val node = evt.node ?: return
        updateProperties(node)
    }

    /** Initializes [.panel].  */
    @Synchronized
    private fun initPanel() {
        if (panel != null) return
        val p = JPanel()
        p.layout = BorderLayout()
        createTree()
        props = JBPanel<Nothing>()
        props.layout = MigLayout("inset 0", "[grow,fill]", "[grow,fill]")
        updateProperties(null)
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT,  //
                JScrollPane(tree),  //
                JScrollPane(props))
        splitPane.dividerLocation = 200
        p.add(splitPane, BorderLayout.CENTER)
        panel = p
    }

    private fun createTree() {
        treeModel = DefaultTreeModel(SwingSceneryTreeNode(sciView))
        tree = JTree(treeModel)
        tree.isRootVisible = true
        tree.cellRenderer = SwingNodePropertyTreeCellRenderer()
        //        tree.setCellEditor(new NodePropertyTreeCellEditor(sciView));
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addTreeSelectionListener { e: TreeSelectionEvent ->
            val sceneNode = sceneNode(e.newLeadSelectionPath)
            sciView.setActiveNode(sceneNode)
            updateProperties(sceneNode)
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                super.mouseClicked(e)
                if (e.clickCount == 2) {
                    val n = tree.lastSelectedPathComponent as? SwingSceneryTreeNode ?: return
                    val node = n.userObject as Node
                    sciView.setActiveCenteredNode(node)
                } else if (e.button == MouseEvent.BUTTON3) {
                    val x = e.x
                    val y = e.y
                    val tree = e.source as JTree
                    val path = tree.getPathForLocation(x, y) ?: return
                    tree.selectionPath = path
                    val obj = path.lastPathComponent as SwingSceneryTreeNode
                    val popup = JPopupMenu()
                    val labelItem = JMenuItem(obj.node.name)
                    labelItem.isEnabled = false
                    popup.add(labelItem)
                    if (obj.node is Camera) {
                        val resetItem = JMenuItem("Reset camera")
                        resetItem.foreground = Color.RED
                        resetItem.addActionListener { _: ActionEvent? ->
                            obj.node.position = Vector3f(0.0f)
                            obj.node.rotation = Quaternionf(0.0f, 0.0f, 0.0f, 1.0f)
                        }
                        popup.add(resetItem)
                    }
                    val hideShow = JMenuItem("Hide")
                    if (obj.node.visible) {
                        hideShow.text = "Hide"
                    } else {
                        hideShow.text = "Show"
                    }
                    hideShow.addActionListener { _: ActionEvent? -> obj.node.visible = !obj.node.visible }
                    popup.add(hideShow)
                    val removeItem = JMenuItem("Remove")
                    removeItem.foreground = Color.RED
                    removeItem.addActionListener { _: ActionEvent? -> sciView.deleteNode(obj.node, true) }
                    popup.add(removeItem)
                    popup.show(tree, x, y)
                } else {
                    val path = tree.getPathForLocation(e.x, e.y)
                    if (path != null && e.x / 1.2 < tree.getPathBounds(path).x + 16) {
                        val n = path.lastPathComponent as? SwingSceneryTreeNode
                        if (n != null) {
                            val node = n.node
                            if (node != null && node !is Camera && node !is Scene) {
                                node.visible = !node.visible
                                tree.repaint()
                            }
                        }
                        e.consume()
                    }
                }
            }
        })
    }

    var currentNode: Node? = null
        private set
    private var currentProperties: Properties? = null
    private lateinit var inputPanel: SwingInputPanel
    private val updateLock = ReentrantLock()

    /** Generates a properties panel for the given node.  */
    fun updateProperties(sceneNode: Node?) {
        if (sceneNode == null) {
            return
        }
        try {
            if (updateLock.tryLock() || updateLock.tryLock(200, TimeUnit.MILLISECONDS)) {
                if (currentNode === sceneNode && currentProperties != null) {
                    currentProperties!!.updateCommandFields()
                    inputPanel.refresh()
                    updateLock.unlock()
                    return
                }
                currentNode = sceneNode
                // Prepare the Properties command module instance.
                val info = commandService.getCommand(Properties::class.java)
                val module = moduleService.createModule(info)
                resolveInjectedInputs(module)
                module.setInput("sciView", sciView)
                module.setInput("sceneNode", sceneNode)
                module.resolveInput("sciView")
                module.resolveInput("sceneNode")
                val p = module.delegateObject as Properties
                currentProperties = p
                p.setSceneNode(sceneNode)

                @Suppress("UNCHECKED_CAST")
                val additionalUI = sceneNode.metadata["sciview-inspector"] as? List<ModuleItem<*>>
                if (additionalUI != null) {
                    for (moduleItem in additionalUI) {
                        p.addInput(moduleItem)
                    }
                }

                // Prepare the SwingInputHarvester.
                val pluginInfo = pluginService.getPlugin(SwingGroupingInputHarvester::class.java)
                val pluginInstance = pluginService.createInstance(pluginInfo)
                val harvester = pluginInstance as SwingGroupingInputHarvester
                inputPanel = harvester.createInputPanel()

                // Build the panel.
                try {
                    harvester.buildPanel(inputPanel, module)
                    updatePropertiesPanel(inputPanel.component)
                } catch (exc: ModuleException) {
                    log.error(exc)
                    val stackTrace = DebugUtils.getStackTrace(exc)
                    val textArea = JTextArea()
                    textArea.text = "<html><pre>$stackTrace</pre>"
                    updatePropertiesPanel(textArea)
                }
                updateLock.unlock()
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun updatePropertiesPanel(c: Component?) {
        props.removeAll()
        if (c == null) {
            val usageLabel = JLabel("<html><em>No node selected.</em><br><br>" +
                    Help.getBasicUsageText(sciView.publicGetInputHandler()) + "</html>")
            usageLabel.preferredSize = Dimension(300, 100)
            props.add(usageLabel)
        } else {
            props.add(c)
            props.size = c.size
        }
        props.validate()
        props.repaint()
    }

    private fun find(root: DefaultMutableTreeNode, n: Node): TreePath? {
        val e = root.depthFirstEnumeration()
        while (e.hasMoreElements()) {
            val node = e.nextElement() as DefaultMutableTreeNode
            if (node.userObject === n) {
                return TreePath(node.path)
            }
        }
        return null
    }

    /** Rebuilds the tree to match the state of the scene.  */
    fun rebuildTree() {
        val currentPath = tree.selectionPath
        treeModel.setRoot(SwingSceneryTreeNode(sciView))

//        treeModel.reload();
//        // TODO: retain previously expanded nodes only
//        for( int i = 0; i < tree.getRowCount(); i++ ) {
//            tree.expandRow( i );
//        }
//        updateProperties( sciView.getActiveNode() );
        if (currentPath != null) {
            val selectedNode = (currentPath.lastPathComponent as SwingSceneryTreeNode).node ?: return
            trySelectNode(selectedNode)
        }
    }

    fun trySelectNode(node: Node) {
        val newPath = find(treeModel.root as DefaultMutableTreeNode, node)
        if (newPath != null) {
            tree.selectionPath = newPath
            tree.scrollPathToVisible(newPath)
            if (node !== sciView.activeNode) {
                updateProperties(node)
            }
        }
    }

    /** Retrieves the scenery node of a given tree node.  */
    private fun sceneNode(treePath: TreePath?): Node? {
        if (treePath == null) return null
        val treeNode = treePath.lastPathComponent as? SwingSceneryTreeNode ?: return null
        val userObject = treeNode.userObject
        return if (userObject !is Node) null else userObject
    }

    /** HACK: Resolve injected [Context] and [Service] inputs.  */
    private fun resolveInjectedInputs(module: Module) {
        for (input in module.info.inputs()) {
            val type = input.type
            if (Context::class.java.isAssignableFrom(type) || Service::class.java.isAssignableFrom(type)) {
                module.resolveInput(input.name)
            }
        }
    }

    init {
        sciView.scijavaContext!!.inject(this)
    }
}