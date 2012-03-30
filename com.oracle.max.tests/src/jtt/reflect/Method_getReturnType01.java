/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
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
 * @Runs: 0="int"; 1="java.lang.String"; 2="void"; 3=null
 */
public class Method_getReturnType01 {

    public static String test(int arg) throws NoSuchMethodException, IllegalAccessException {
        if (arg == 0) {
            return Method_getReturnType01.class.getMethod("method1").getReturnType().getName();
        } else if (arg == 1) {
            return Method_getReturnType01.class.getMethod("method2").getReturnType().getName();
        } else if (arg == 2) {
            return Method_getReturnType01.class.getMethod("method3").getReturnType().getName();
        }
        return null;
    }

    public int method1() {
        return 0;
    }
    public String method2() {
        return null;
    }
    public void method3() {
    }
}