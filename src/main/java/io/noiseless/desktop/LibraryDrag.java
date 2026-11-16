package io.noiseless.desktop;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.datatransfer.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.*;
import com.fasterxml.jackson.databind.JsonNode;
import static io.noiseless.core.Json.*;

final class LibraryDrag extends TransferHandler {
    private final JTree tree;
    private final Function<DefaultMutableTreeNode,String> key;
    private final Supplier<JsonNode> catalog;
    private final Consumer<JsonNode> apply;
    LibraryDrag(JTree tree,Function<DefaultMutableTreeNode,String> key,Supplier<JsonNode> catalog,Consumer<JsonNode> apply){ this.tree=tree; this.key=key; this.catalog=catalog; this.apply=apply; tree.setDragEnabled(false); tree.setDropMode(DropMode.ON_OR_INSERT); installMouseDrag(); }
    private void installMouseDrag(){
        var mouse=new MouseAdapter(){ String source; Point origin; boolean dragging; JsonNode pending;
            public void mousePressed(MouseEvent e){ if(!SwingUtilities.isLeftMouseButton(e)) return; TreePath path=tree.getPathForLocation(e.getX(),e.getY()); if(path!=null){ source=key.apply((DefaultMutableTreeNode)path.getLastPathComponent()); origin=e.getPoint(); } }
            public void mouseDragged(MouseEvent e){
                if(source==null||origin==null||origin.distance(e.getPoint())<6) return; dragging=true; tree.putClientProperty("noiseless.drag",true); tree.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                TreePath path=tree.getPathForLocation(e.getX(),e.getY()); pending=null;
                if(path==null) pending=source.startsWith("folder:")?folder(source,""):article(source,"","");
                else{ var target=(DefaultMutableTreeNode)path.getLastPathComponent(); String id=key.apply(target); Rectangle bounds=tree.getPathBounds(path); boolean before=e.getY()<bounds.getCenterY();
                    if(source.startsWith("folder:")){ if(id.startsWith("folder:")&&!source.equals(id)){ var action=obj("action","move-folder","id",source.substring(7)); action.put(before?"before":"after",id.substring(7)); pending=action; } }
                    else if(id.startsWith("folder:")) pending=article(source,id.substring(7),"");
                    else if(!id.equals(source)){ String folder=catalog.get().path("library").path("articles").path(id).path("folder").asText(); var action=obj("action","move-article","file",source,"folder",folder); action.put(before?"before":"after",id); pending=action; }
                    tree.putClientProperty("noiseless.dropY",id.startsWith("folder:")?bounds.y:before?bounds.y:bounds.y+bounds.height);
                }
                Rectangle visible=tree.getVisibleRect(); if(e.getY()<visible.y+24) tree.scrollRectToVisible(new Rectangle(0,Math.max(0,visible.y-20),1,1)); if(e.getY()>visible.y+visible.height-24) tree.scrollRectToVisible(new Rectangle(0,visible.y+visible.height+20,1,1)); tree.repaint();
            }
            public void mouseReleased(MouseEvent e){ if(SwingUtilities.isLeftMouseButton(e)&&origin!=null&&origin.distance(e.getPoint())>=6) mouseDragged(e); if(dragging&&pending!=null) apply.accept(pending); source=null; origin=null; dragging=false; pending=null; tree.putClientProperty("noiseless.dropY",null); tree.setCursor(Cursor.getDefaultCursor()); tree.repaint(); SwingUtilities.invokeLater(()->tree.putClientProperty("noiseless.drag",false)); }
        }; tree.addMouseListener(mouse); tree.addMouseMotionListener(mouse);
    }
    @Override public int getSourceActions(JComponent c){ return MOVE; }
    @Override protected Transferable createTransferable(JComponent c){ var path=tree.getSelectionPath(); return path==null?null:new StringSelection(key.apply((DefaultMutableTreeNode)path.getLastPathComponent())); }
    @Override public boolean canImport(TransferSupport support){ if(!support.isDrop()||!support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false; try{ return action(support)!=null; }catch(Exception e){ return false; } }
    @Override public boolean importData(TransferSupport support){ try{ var action=action(support); if(action==null) return false; apply.accept(action); return true; }catch(Exception e){ return false; } }
    private JsonNode action(TransferSupport support) throws Exception {
        String source=(String)support.getTransferable().getTransferData(DataFlavor.stringFlavor); var drop=(JTree.DropLocation)support.getDropLocation(); TreePath path=drop.getPath(); if(path==null) return null;
        var node=(DefaultMutableTreeNode)path.getLastPathComponent(); int insertion=drop.getChildIndex(); String target=key.apply(node);
        if(source.startsWith("folder:")){
            if(!node.isRoot()&&insertion<0&&!target.startsWith("folder:")) return null;
            String anchor=target.startsWith("folder:")?target:"";
            if(insertion>=0) for(int i=insertion;i<node.getChildCount();i++){ String next=key.apply((DefaultMutableTreeNode)node.getChildAt(i)); if(next.startsWith("folder:")){ anchor=next; break; } }
            return folder(source,anchor);
        }
        String folder="",before="";
        if(insertion<0){ if(target.startsWith("folder:")) folder=target.substring(7); else if(!node.isRoot()){ before=target; folder=catalog.get().path("library").path("articles").path(target).path("folder").asText(); } }
        else { if(target.startsWith("folder:")) folder=target.substring(7); for(int i=insertion;i<node.getChildCount();i++){ String next=key.apply((DefaultMutableTreeNode)node.getChildAt(i)); if(!next.startsWith("folder:")){ before=next; break; } } }
        return article(source,folder,before);
    }
    static JsonNode folder(String source,String anchor){ if(source.equals(anchor)) return null; var action=obj("action","move-folder","id",source.substring(7)); if(!anchor.isBlank()) action.put("before",anchor.substring(7)); return action; }
    static JsonNode article(String source,String folder,String before){ if(source.equals(before)) return null; var action=obj("action","move-article","file",source,"folder",folder); if(!before.isBlank()) action.put("before",before); return action; }
}
