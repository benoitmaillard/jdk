/*
 * Copyright (c) 2017, 2025, Oracle and/or its affiliates. All rights reserved.
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


/*
 * @test
 * @key stress
 *
 * @summary converted from VM Testbase nsk/monitoring/stress/thread/strace010.
 * VM Testbase keywords: [stress, monitoring, nonconcurrent]
 * VM Testbase readme:
 * DESCRIPTION
 *     The test starts 50 recursive threads, switches them  to the various
 *     state after reaching 200 depth and checks up their stack traces
 *     and states gotten via the ThreadMXBean interface.
 *     Access to the management metrics is accomplished by DIRECTLY calling
 *     the methods in the MBean.
 * COMMENTS
 *     Fixed the bug:
 *     4953476 TEST_BUG: The spec is updated accoring to 4944573 and 4947536
 *     Fixed the bug:
 *     4969687 TEST_BUG: The spec is updated accoring to 4956978, 4957000, 4959889
 *     Reduced recursion depth value up to 100 because the test fails
 *     due to timeout in -Xint mode on solaris-sparc(Sun Ultra-10, 333 MHz, 256Mb)
 *     Adjusted according to
 *     5014783 Move ThreadState class from java.lang.management to java.lang
 *
 * @library /vmTestbase
 *          /test/lib
 * @run main/othervm/native nsk.monitoring.stress.thread.strace010 -depth=100 -threadCount=30
 */
