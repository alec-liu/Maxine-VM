/*
 * Copyright (c) 2009, 2010, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.max.ins.gui;

import java.awt.*;

import javax.swing.*;

import com.sun.max.ins.gui.Inspector.MenuKind;
import com.sun.max.ins.util.*;

/**
 * A frame suitable for use by an {@linkplain Inspector inspector}.
 * This is a minimal frame without window system decoration, suitable
 * for used in a tabbed container of inspectors.
 *
 * @author Michael Van De Vanter
 */
final class InspectorRootPane<Inspector_Type extends Inspector> extends JRootPane implements InspectorFrame {

    private final Inspector_Type inspector;
    private final TabbedInspector<Inspector_Type> parent;
    private final InspectorMenuBar menuBar;

    private String title = null;

    /**
     * Creates a simple frame, with content pane, for an Inspector intended to be in a
     * tabbed frame.
     * <br>
     * The frame has an optional menu bar.  It is a program error to call {@link #makeMenu(MenuKind)}
     * if no menu bar is present.
     *
     * @param inspector the inspector that owns this frame
     * @param parent the tabbed frame that will own this frame.
     * @param addMenuBar  should the frame have a menu bar installed.
     * @see #makeMenu(MenuKind)
     */
    public InspectorRootPane(Inspector_Type inspector, TabbedInspector<Inspector_Type> parent, boolean addMenuBar) {
        this.inspector = inspector;
        this.parent = parent;
        menuBar = addMenuBar ? new InspectorMenuBar(inspector.inspection()) : null;
        setJMenuBar(menuBar);
    }

    public JComponent getJComponent() {
        return this;
    }

    public void refresh(boolean force) {
        if (menuBar != null) {
            menuBar.refresh(force);
        }
    }

    public void redisplay() {
    }

    public Inspector inspector() {
        return inspector;
    }

    public InspectorMenu makeMenu(MenuKind menuKind) throws InspectorError {
        InspectorError.check(menuBar != null);
        return menuBar.makeMenu(menuKind);
    }

    public void setSelected() {
        parent.setSelected(inspector);
    }

    public boolean isSelected() {
        return parent.isSelected() && parent.isSelected(inspector);
    }

    public void flash(Color borderFlashColor) {
        Component pane = getContentPane();
        if (pane instanceof JScrollPane) {
            final JScrollPane scrollPane = (JScrollPane) pane;
            pane = scrollPane.getViewport();
        }
        final Graphics g = pane.getGraphics();
        g.setPaintMode();
        g.setColor(borderFlashColor);
        for (int i = 0; i < 5; i++) {
            g.drawRect(i, i, pane.getWidth() - (i * 2), pane.getHeight() - (i * 2));
        }
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
        }
        g.dispose();
        invalidate();
        repaint();
    }

    public void setStateColor(Color color) {
        if (menuBar != null) {
            menuBar.setBackground(color);
        }
    }

    public void dispose() {
        parent.remove(this);
        inspector.inspectorClosing();
    }

    public String getTitle() {
        return title;
    }

    public void moveToFront() {
        parent.moveToFront();
        setSelected();
    }

    public void pack() {
        setSize(getPreferredSize());
        validate();
    }

    public void setTitle(String title) {
        this.title = title;
    }

}
