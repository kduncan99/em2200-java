/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.kex.kasm;

import com.kadware.komodo.kex.RelocatableModule;
import com.kadware.komodo.kex.kasm.diagnostics.Diagnostic;
import com.kadware.komodo.kex.kasm.exceptions.ParameterException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for the Assembler class in this package
 */
public class Test_Diagnostics {

    private static final AssemblerOption[] OPTIONS = {
        AssemblerOption.EMIT_MODULE_SUMMARY,
        AssemblerOption.EMIT_SOURCE,
        AssemblerOption.EMIT_GENERATED_CODE,
        AssemblerOption.EMIT_DICTIONARY,
        };
    private static final Set<AssemblerOption> OPTION_SET = new HashSet<>(Arrays.asList(OPTIONS));

    @Test
    public void formDiag1(
    ) {
        String[] source = {
            "F1       $FORM     12,12,12,12",
        };

        Assembler assembler = new Assembler.Builder().setOptions(OPTION_SET)
                                                     .setSource(source)
                                                     .build();
        AssemblerResult result = assembler.assemble();

        assertFalse(result._diagnostics.isEmpty());
        assertFalse(result._diagnostics.hasFatal());
        assertEquals(1, (int)result._diagnostics.getCounters().get(Diagnostic.Level.Form));
    }

    @Test
    public void formDiag2(
    ) {
        String[] source = {
            "F1       $FORM     12,12,12",
            "         F1        1,2,3,4",
        };

        Assembler assembler = new Assembler.Builder().setOptions(OPTION_SET)
                                                     .setSource(source)
                                                     .build();
        AssemblerResult result = assembler.assemble();

        assertFalse(result._diagnostics.isEmpty());
        assertFalse(result._diagnostics.hasFatal());
        assertEquals(1, (int)result._diagnostics.getCounters().get(Diagnostic.Level.Form));
    }

    @Test
    public void formOK(
    ) throws ParameterException {
        String[] source = {
            "F1       $FORM     12,12,12",
            "         -2",
            "         3-5",
            "         F1        1,-2,3",
        };

        Assembler assembler = new Assembler.Builder().setOptions(OPTION_SET)
                                                     .setSource(source)
                                                     .build();
        AssemblerResult result = assembler.assemble();

        assertTrue(result._diagnostics.isEmpty());
        assertNotNull(result._relocatableModule);
        RelocatableModule.RelocatablePool lcPool = result._relocatableModule.getLocationCounterPool(0);
        assertEquals(3, lcPool._content.length);
        assertEquals(0_777777_777775L, lcPool._content[0].getW());
        assertEquals(0_777777_777775L, lcPool._content[1].getW());
        assertEquals(0_0001_7775_0003L, lcPool._content[2].getW());
    }

}
