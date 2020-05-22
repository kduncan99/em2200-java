/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.kex.kasm.directives;

import com.kadware.komodo.kex.kasm.Assembler;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class Test_Equf {

    private static final Assembler.Option[] OPTIONS = {
        Assembler.Option.EMIT_MODULE_SUMMARY,
        Assembler.Option.EMIT_SOURCE,
        Assembler.Option.EMIT_GENERATED_CODE,
        Assembler.Option.EMIT_DICTIONARY,
        };
    private static final Set<Assembler.Option> OPTION_SET = new HashSet<>(Arrays.asList(OPTIONS));


    @Test
    public void simple_basic(
    ) {
        String[] source = {
            "          $BASIC",
            "LABEL     $EQUF *5,*X3,S1,B12",
            //TODO generate something
        };

        Assembler asm = new Assembler.Builder().setModuleName("TEST")
                                               .setOptions(OPTION_SET)
                                               .setSource(source)
                                               .build();
        Assembler.Result result = asm.assemble();
        assertTrue(result._diagnostics.isEmpty());
        //TODO test generated value
    }

    @Test
    public void simple_extended(
    ) {
        String[] source = {
            "          $EXTEND",
            "LABEL     $EQUF *5,*X3,S1,B12",
            //TODO generate something
        };

        Assembler asm = new Assembler.Builder().setModuleName("TEST")
                                               .setOptions(OPTION_SET)
                                               .setSource(source)
                                               .build();
        Assembler.Result result = asm.assemble();
        assertTrue(result._diagnostics.isEmpty());
        //TODO test generated value
    }
}