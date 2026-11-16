package io.noiseless.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
import java.util.function.*;

/** Shared reducer for native gesture phases and Shift+horizontal wheel fallback. */
final class SwipeNavigation {
    static final class Gesture {
        double total; boolean tracking,nativePhase,locked;
        final IntPredicate canGo; final IntConsumer navigate; final DoubleConsumer feedback;
        Gesture(IntPredicate canGo,IntConsumer navigate,DoubleConsumer feedback){ this.canGo=canGo; this.navigate=navigate; this.feedback=feedback; }
        void begin(){ total=0; tracking=false; locked=false; nativePhase=true; feedback.accept(0); }
        boolean move(double delta){ if(locked) return true; if(delta==0) return tracking; if(!tracking&&!canGo.test(delta<0?-1:1)) return false; tracking=true; total+=delta; feedback.accept(canGo.test(total<0?-1:1)?Math.copySign(Math.min(110,Math.abs(total))*.65,total):0); return true; }
        void end(){ if(locked) return; int direction=total<0?-1:1; if(tracking&&Math.abs(total)>=110&&canGo.test(direction)) navigate.accept(direction); tracking=false; locked=true; feedback.accept(0); }
        void cancel(){ total=0; tracking=false; locked=true; feedback.accept(0); }
        void resetFallback(){ if(!nativePhase){ locked=false; total=0; } }
        void discrete(int direction){ if(!tracking&&!locked&&canGo.test(direction)){ navigate.accept(direction); locked=true; feedback.accept(0); } }
    }
    private final Gesture gesture;
    private final javax.swing.Timer release,reset;
    SwipeNavigation(JFrame frame,IntPredicate canGo,IntConsumer navigate){
        JLabel arrow=new JLabel("",SwingConstants.CENTER); arrow.setOpaque(true); arrow.setBackground(new Color(0xf2f4f2)); arrow.setForeground(NativeTheme.GREEN); arrow.setFont(NativeTheme.font(25)); frame.getLayeredPane().add(arrow,JLayeredPane.POPUP_LAYER); arrow.setVisible(false);
        gesture=new Gesture(canGo,navigate,d->{ arrow.setText(d<0?"‹":"›"); arrow.setBounds(d<0?(int)(65+Math.abs(d)):(int)(frame.getWidth()-110-Math.abs(d)),Math.max(80,frame.getHeight()/2),32,38); arrow.setVisible(d!=0); });
        reset=new javax.swing.Timer(240,e->gesture.resetFallback()); reset.setRepeats(false);
        release=new javax.swing.Timer(200,e->{ gesture.end(); reset.restart(); }); release.setRepeats(false);
        Toolkit.getDefaultToolkit().addAWTEventListener(e->{ if(e instanceof MouseWheelEvent wheel&&wheel.isShiftDown()&&SwingUtilities.getWindowAncestor(wheel.getComponent())==frame){ if(gesture.move(wheel.getPreciseWheelRotation()*24)){ wheel.consume(); if(!gesture.nativePhase) { if(gesture.locked) reset.restart(); else release.restart(); } } } },AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        installMac(frame.getRootPane());
    }
    private void installMac(JComponent root){
        if(!System.getProperty("os.name").contains("Mac")) return;
        try{
            Class<?> swipe=Class.forName("com.apple.eawt.event.SwipeListener"),phase=Class.forName("com.apple.eawt.event.GesturePhaseListener"),utilities=Class.forName("com.apple.eawt.event.GestureUtilities"),listener=Class.forName("com.apple.eawt.event.GestureListener");
            Object proxy=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{swipe,phase},(p,m,args)->{
                switch(m.getName()){
                    case "gestureBegan" -> { release.stop(); reset.stop(); gesture.begin(); }
                    case "gestureEnded" -> gesture.end();
                    case "swipedLeft" -> gesture.discrete(-1);
                    case "swipedRight" -> gesture.discrete(1);
                    case "hashCode" -> { return System.identityHashCode(p); }
                    case "equals" -> { return args[0]==p; }
                    case "toString" -> { return "Noiseless gestures"; }
                    default -> {}
                } return null;
            }); utilities.getMethod("addGestureListenerTo",JComponent.class,listener).invoke(null,root,proxy);
        }catch(ReflectiveOperationException|IllegalArgumentException e){ /* Wheel and keyboard navigation remain available on other runtimes. */ }
    }
}
