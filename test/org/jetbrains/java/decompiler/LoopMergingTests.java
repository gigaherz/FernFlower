package org.jetbrains.java.decompiler;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

public class LoopMergingTests extends SingleClassesTestBase {

    @Before
    public void setUp() throws IOException {
        fixture = new DecompilerTestFixture();
        fixture.setUp();
        fixture.cleanup = false;
    }

    @Test
    public void testLoopMerging() {
        doTest("pkg/TestLoopMerging");
    }

}
