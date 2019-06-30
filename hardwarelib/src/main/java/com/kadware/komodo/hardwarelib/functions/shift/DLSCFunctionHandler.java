/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.functions.shift;

import com.kadware.komodo.baselib.InstructionWord;
import com.kadware.komodo.baselib.OnesComplement;
import com.kadware.komodo.hardwarelib.InstructionProcessor;
import com.kadware.komodo.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.komodo.hardwarelib.interrupts.MachineInterrupt;
import com.kadware.komodo.hardwarelib.functions.InstructionHandler;

/**
 * Handles the DLSC instruction f=073 j=007
 */
public class DLSCFunctionHandler extends InstructionHandler {

    private final long[] _operand = new long[2];

    @Override
    public synchronized void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        //  Retrieve double-precision (U). Shift left circularly until bit0 != bit1,
        //  then store the result into A(a)/A(a+1) and the number of shifts done into A(a+2).
        //  If (U) already has bit0 != bit1, then store (U) in A(a) and 0 in A(a+1)
        //  If (U) is all zeros or all ones, store it in A(a) and 71 in A(a+1)
        ip.getConsecutiveOperands(true, _operand);
        int count = 0;
        if (OnesComplement.isZero72(_operand)) {
            count = 71;
        } else {
            long bits = _operand[0] & 0_600000_000000l;
            while ((bits == 0) || (bits == 0_600000_000000l)) {
                OnesComplement.leftShiftCircular72(_operand, 1, _operand);
                ++count;
                bits = _operand[0] & 0_600000_000000l;
            }
        }

        ip.getExecOrUserARegister((int)iw.getA()).setW(_operand[0]);
        ip.getExecOrUserARegister((int)iw.getA() + 1).setW(_operand[1]);
        ip.getExecOrUserARegister((int)iw.getA() + 2).setW(count);
    }

    @Override
    public Instruction getInstruction() { return Instruction.DLSC; }
}
