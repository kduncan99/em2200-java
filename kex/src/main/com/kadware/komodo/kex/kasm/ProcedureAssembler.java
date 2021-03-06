/*
 * Copyright (c) 2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.kex.kasm;

public class ProcedureAssembler extends SubAssembler {

    ProcedureAssembler(
        final Assembler outerLevel,
        final String subModuleName,
        final TextLine[] sourceLines
    ) {
        super(outerLevel, subModuleName, sourceLines);
    }

    @Override
    public boolean isFunctionSubAssembly() {
        return false;
    }

    @Override
    public boolean isProcedureSubAssembly() {
        return true;
    }
}
