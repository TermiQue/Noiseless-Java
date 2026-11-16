package io.noiseless.desktop;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InteractionTest {
    @Test void swipeCommitsOnReleaseAndSuppressesMomentum(){
        var calls=new ArrayList<Integer>(); var feedback=new ArrayList<Double>(); var gesture=new SwipeNavigation.Gesture(d->true,calls::add,feedback::add);
        gesture.begin(); gesture.move(-150); assertTrue(calls.isEmpty()); gesture.move(80); gesture.end(); assertTrue(calls.isEmpty());
        gesture.begin(); gesture.move(-120); gesture.end(); assertEquals(List.of(-1),calls); gesture.move(-300); gesture.end(); assertEquals(1,calls.size());
        gesture.begin(); gesture.move(180); gesture.cancel(); gesture.end(); assertEquals(1,calls.size());
        gesture.begin(); gesture.move(120); gesture.end(); assertEquals(List.of(-1,1),calls);
    }
    @Test void dragResolvesFoldersOrderingAndSelfDrop(){
        assertEquals("folder",LibraryDrag.article("article","folder","other").path("folder").asText());
        assertEquals("other",LibraryDrag.article("article","folder","other").path("before").asText());
        assertNull(LibraryDrag.article("same","","same")); assertNull(LibraryDrag.folder("folder:a","folder:a"));
        assertEquals("b",LibraryDrag.folder("folder:a","folder:b").path("before").asText());
    }
    @Test void browserTabsRetainDraftsAndCancelOnlyClosedTab() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            ToolTabs tabs=new ToolTabs(); JTextArea first=new JTextArea("draft one"),second=new JTextArea("draft two"); var closed=new ArrayList<String>();
            tabs.addTab("One",first); tabs.onClose(first,()->closed.add("one")); tabs.addTab("Two",second); tabs.setSelectedComponent(first);
            assertEquals("draft one",((JTextArea)tabs.getSelectedComponent()).getText()); tabs.remove(second); assertTrue(closed.isEmpty());
            tabs.remove(first); assertEquals(List.of("one"),closed); assertEquals(0,tabs.getTabCount());
        });
    }
    @Test void transcriptEscapesHtmlAndNeverLoadsModelImages() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            Transcript transcript=new Transcript(); transcript.message("assistant","<script>alert(1)</script>\n\n![private](http://127.0.0.1:1/x)\n\n| A | B |\n|---|---|\n| x | y |",null);
            String html=transcript.getText(); assertFalse(html.contains("<script>")); assertFalse(html.contains("<img")); assertTrue(html.contains("<table"));
            transcript.streaming("partial"); transcript.message("assistant","final",null); assertFalse(transcript.getText().contains("partial"));
        });
    }
    @Test void internalMouseDragCommitsOneLibraryAction() throws Exception { checkMouseDrop(true); }
    @Test void coalescedMouseDragCommitsReleasePosition() throws Exception { checkMouseDrop(false); }
    private void checkMouseDrop(boolean intermediateEvent) throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var root=new javax.swing.tree.DefaultMutableTreeNode("root"); var article=new javax.swing.tree.DefaultMutableTreeNode("article"); var folder=new javax.swing.tree.DefaultMutableTreeNode("folder:target"); root.add(article); root.add(folder); JTree tree=new JTree(root); tree.setRootVisible(false); tree.setRowHeight(40); tree.setSize(220,400);
            var applied=new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>(); new LibraryDrag(tree,n->n.isRoot()?"":n.getUserObject().toString(),()->io.noiseless.core.Json.obj(),applied::add);
            var from=tree.getPathBounds(new javax.swing.tree.TreePath(article.getPath())); var to=tree.getPathBounds(new javax.swing.tree.TreePath(folder.getPath()));
            var listener=java.util.Arrays.stream(tree.getMouseListeners()).filter(l->l.getClass().getName().contains("LibraryDrag")).findFirst().orElseThrow(); var motion=java.util.Arrays.stream(tree.getMouseMotionListeners()).filter(l->l.getClass().getName().contains("LibraryDrag")).findFirst().orElseThrow();
            listener.mousePressed(new java.awt.event.MouseEvent(tree,java.awt.event.MouseEvent.MOUSE_PRESSED,0,java.awt.event.InputEvent.BUTTON1_DOWN_MASK,from.x+10,from.y+15,1,false,java.awt.event.MouseEvent.BUTTON1));
            if(intermediateEvent) motion.mouseDragged(new java.awt.event.MouseEvent(tree,java.awt.event.MouseEvent.MOUSE_DRAGGED,1,java.awt.event.InputEvent.BUTTON1_DOWN_MASK,to.x+10,to.y+15,0,false,java.awt.event.MouseEvent.NOBUTTON));
            listener.mouseReleased(new java.awt.event.MouseEvent(tree,java.awt.event.MouseEvent.MOUSE_RELEASED,2,0,to.x+10,to.y+15,1,false,java.awt.event.MouseEvent.BUTTON1));
            assertEquals(1,applied.size()); assertEquals("target",applied.getFirst().path("folder").asText()); assertEquals("article",applied.getFirst().path("file").asText());
        });
    }
}
