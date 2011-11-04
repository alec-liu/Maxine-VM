/*
 * Copyright (c) 2007, 2011, Oracle and/or its affiliates. All rights reserved.
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
package jtt.reflect;

/*
 * @Harness: java
 * @Runs: 0=0; 1=1; 2=2; 3=-1
 */
public class Method_getParameterTypes01 {

    public static int test(int arg) throws NoSuchMethodException, IllegalAccessException {
        if (arg == 0) {
            return Method_getParameterTypes01.class.getMethod("method1").getParameterTypes().length;
        } else if (arg == 1) {
            return Method_getParameterTypes01.class.getMethod("method2", int.class).getParameterTypes().length;
        } else if (arg == 2) {
            return Method_getParameterTypes01.class.getMethod("method3", int.class, Object.class).getParameterTypes().length;
        }
        return -1;
    }

    public int method1() {
        return 0;
    }
    public void method2(int arg1) {
    }
    public void method3(int arg1, Object arg2) {
    }
}
