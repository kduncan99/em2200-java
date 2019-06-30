/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.functions.fixedPointBinary;

import com.kadware.komodo.baselib.InstructionWord;
import com.kadware.komodo.baselib.OnesComplement;
import com.kadware.komodo.hardwarelib.InstructionProcessor;
import com.kadware.komodo.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.komodo.hardwarelib.interrupts.MachineInterrupt;
import com.kadware.komodo.hardwarelib.interrupts.OperationTrapInterrupt;
import com.kadware.komodo.hardwarelib.functions.InstructionHandler;

/**
 * Handles the DA instruction f=071 j=010
 */
@SuppressWarnings("Duplicates")
public class DAFunctionHandler extends InstructionHandler {

    private final long[] _operand1 = { 0, 0 };
    private final long[] _operand2 = { 0, 0 };
    private final OnesComplement.Add72Result _ar = new OnesComplement.Add72Result();

    @Override
    public synchronized void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        _operand1[0] = ip.getExecOrUserARegister((int)iw.getA()).getW();
        _operand1[1] = ip.getExecOrUserARegister((int)iw.getA() + 1).getW();
        ip.getConsecutiveOperands(true, _operand2);

        OnesComplement.add72(_operand1, _operand2, _ar);

        ip.getExecOrUserARegister((int)iw.getA()).setW(_ar._sum[0]);
        ip.getExecOrUserARegister((int)iw.getA() + 1).setW(_ar._sum[1]);

        ip.getDesignatorRegister().setCarry(_ar._carry);
        ip.getDesignatorRegister().setOverflow(_ar._overflow);
        if (ip.getDesignatorRegister().getOperationTrapEnabled() && _ar._overflow) {
            throw new OperationTrapInterrupt(OperationTrapInterrupt.Reason.FixedPointBinaryIntegerOverflow);
        }
    }

    @Override
    public Instruction getInstruction() { return Instruction.DA; }
}
