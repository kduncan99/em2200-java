/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.em2200.hardwarelib;

import com.kadware.em2200.baselib.*;
import com.kadware.em2200.hardwarelib.exceptions.*;
import com.kadware.em2200.hardwarelib.functions.*;
import com.kadware.em2200.hardwarelib.misc.*;
import com.kadware.em2200.hardwarelib.interrupts.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base class which models an Instruction Procesor node
 */
public class InstructionProcessor extends Processor implements Worker {

    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Nested enumerations
    //  ----------------------------------------------------------------------------------------------------------------------------

    public enum RunMode {
        Normal,
        SingleInstruction,
        SingleCycle,
    };

    public enum StopReason {
        Initial,
        Cleared,
        Debug,
        Development,
        Breakpoint,
        HaltJumpExecuted,
        ICSBaseRegisterInvalid,
        ICSOverflow,
        InitiateAutoRecovery,
        L0BaseRegisterInvalid,
        PanelHalt,

        // Interrupt Handler initiated stops...
        InterruptHandlerHardwareFailure,
        InterruptHandlerOffsetOutOfRange,
        InterruptHandlerInvalidBankType,
        InterruptHandlerInvalidLevelBDI,
    };

    public enum BreakpointComparison {
        Fetch,
        Read,
        Write,
    };


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Nested classes
    //  ----------------------------------------------------------------------------------------------------------------------------

    private static class BasicModeBankName extends Word36 {

        private BasicModeBankName(
            boolean execFlag,
            int level,
            int bankDescriptorIndex
        ) {
            _value = ((execFlag ? 1L : 0) << 35) | ((level & 01) << 32) | ((bankDescriptorIndex & 07777) << 18);
        }

        private boolean getExecFlag() { return (_value >> 35) != 0; }
        private int getLevel() { return (int)(_value >> 32) & 01; }
        private int getBankDescriptorIndex() { return (int)(_value >> 18) & 07777; }

        private ExtendedModeBankName translate() {
            if (getExecFlag()) {
                if (getLevel() == 1) {
                    return new ExtendedModeBankName(0, getBankDescriptorIndex());
                } else {
                    return new ExtendedModeBankName(2, getBankDescriptorIndex());
                }
            } else {
                if (getLevel() == 0) {
                    return new ExtendedModeBankName(4, getBankDescriptorIndex());
                } else {
                    return new ExtendedModeBankName(6, getBankDescriptorIndex());
                }
            }
        }
    }

    private static class ExtendedModeBankName extends Word36 {

        ExtendedModeBankName(
            int level,
            int bankDescriptorIndex
        ) {
            _value = ((level & 07) << 33) | ((bankDescriptorIndex & 077777) << 18);
        }

        private int getLevel() { return (int)(_value >> 33); }
        private int getBankDescriptorIndex() { return (int)(_value >> 18) & 077777; }

        private BasicModeBankName translate() {
            int bdi = getBankDescriptorIndex();
            if (bdi > 07777) {
                return new BasicModeBankName(true, 1, 0);
            } else {
                switch (getLevel()) {
                    case 0:     return new BasicModeBankName(true, 1, bdi);
                    case 2:     return new BasicModeBankName(true, 0, bdi);
                    case 4:     return new BasicModeBankName(false, 0, bdi);
                    case 6:     return new BasicModeBankName(false, 1, bdi);
                    default:    return new BasicModeBankName(true, 1, 0);
                }
            }
        }
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Class attributes
    //  ----------------------------------------------------------------------------------------------------------------------------

    public static final int L0_BDT_BASE_REGISTER            = 16;
    public static final int ICS_BASE_REGISTER               = 26;
    public static final int ICS_INDEX_REGISTER              = GeneralRegisterSet.EX1;
    public static final int RCS_BASE_REGISTER               = 25;
    public static final int RCS_INDEX_REGISTER              = GeneralRegisterSet.EX0;

    /**
     * Raise interrupt when this many new entries exist
     */
    private static final int JUMP_HISTORY_TABLE_THRESHOLD   = 120;

    /**
     * Size of the conditionalJump history table
     */
    private static final int JUMP_HISTORY_TABLE_SIZE        = 128;

    private static final Logger LOGGER = LogManager.getLogger(InstructionProcessor.class);

    /**
     * Order of base register selection for Basic Mode address resolution
     * when the Basic Mode Base Register Selection Designator Register bit is false
     */
    private static final int[] BASE_REGISTER_CANDIDATES_FALSE = {12, 14, 13, 15};

    /**
     * Order of base register selection for Basic Mode address resolution
     * when the Basic Mode Base Register Selection Designator Register bit is true
     */
    private static final int[] BASE_REGISTER_CANDIDATES_TRUE = {13, 15, 12, 14};

    /**
     * ActiveBaseTable entries - index 0 is for B1 .. index 14 is for B15.  There is no entry for B0.
     */
    private final ActiveBaseTableEntry[] _activeBaseTableEntries = new ActiveBaseTableEntry[15];

    /**
     * Storage locks...
     */
    private static final Map<InstructionProcessor, HashSet<AbsoluteAddress>> _storageLocks = new HashMap<>();

    private final BaseRegister[]            _baseRegisters = new BaseRegister[32];
    private final AbsoluteAddress           _breakpointAddress = new AbsoluteAddress((short)0, 0, -1);
    private final BreakpointRegister        _breakpointRegister = new BreakpointRegister();
    private boolean                         _broadcastInterruptEligibility = false;
    private final InstructionWord           _currentInstruction = new InstructionWord();
    private InstructionHandler _currentInstructionHandler = null;  //  TODO do we need this?
    private RunMode                         _currentRunMode = RunMode.Normal;   //  TODO why isn't this updated?
    private final DesignatorRegister        _designatorRegister = new DesignatorRegister();
    private boolean                         _developmentMode = true;    //  TODO default this to false and provide a means of changing it
    private final GeneralRegisterSet        _generalRegisterSet = new GeneralRegisterSet();
    private final IndicatorKeyRegister      _indicatorKeyRegister = new IndicatorKeyRegister();
    private final InventoryManager          _inventoryManager = InventoryManager.getInstance();
    private boolean                         _jumpHistoryFullInterruptEnabled = false;
    private final Word36[]                  _jumpHistoryTable = new Word36[JUMP_HISTORY_TABLE_SIZE];
    private int                             _jumpHistoryTableNext = 0;
    private boolean                         _jumpHistoryThresholdReached = false;
    private MachineInterrupt                _lastInterrupt = null;    //  must always be != _pendingInterrupt
    private long                            _latestStopDetail = 0;
    private StopReason                      _latestStopReason = StopReason.Initial;
    private boolean                         _midInstructionInterruptPoint = false;
    private MachineInterrupt                _pendingInterrupt = null;
    private final ProgramAddressRegister    _preservedProgramAddressRegister = new ProgramAddressRegister();    //  TODO do we need this?
    private boolean                         _preventProgramCounterIncrement = false;
    private final ProgramAddressRegister    _programAddressRegister = new ProgramAddressRegister();
    private final Word36                    _quantumTimer = new Word36();
    private boolean                         _runningFlag = false;


    /**
     * Set this to cause the worker thread to shut down
     */
    private boolean _workerTerminate = false;

    /**
     * reference to worker thread
     */
    private final Thread _workerThread;


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Constructors
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * Constructor
     * @param name node name
     * @param upi unique identifier for this processor
     */
    public InstructionProcessor(
        final String name,
        final short upi
    ) {
        super(Processor.ProcessorType.InstructionProcessor, name, upi);

        _storageLocks.put(this, new HashSet<AbsoluteAddress>());

        for (int bx = 0; bx < _baseRegisters.length; ++bx) {
            _baseRegisters[bx] = new BaseRegister();
        }

        _workerThread = new Thread(this);
        _workerTerminate = false;

        for (int jx = 0; jx < JUMP_HISTORY_TABLE_SIZE; ++jx) {
            _jumpHistoryTable[jx] = new Word36();
        }

        for (int ax = 0; ax < _activeBaseTableEntries.length; ++ax) {
            _activeBaseTableEntries[ax] = new ActiveBaseTableEntry(0);
        }
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Accessors
    //  ----------------------------------------------------------------------------------------------------------------------------


    public ActiveBaseTableEntry[] getActiveBaseTableEntries() { return _activeBaseTableEntries; }
    public BaseRegister getBaseRegister(final int index) { return _baseRegisters[index]; }
    public boolean getBroadcastInterruptEligibility() { return _broadcastInterruptEligibility; }
    public InstructionWord getCurrentInstruction() { return _currentInstruction; }
    public RunMode getCurrentRunMode() { return _currentRunMode; }
    public DesignatorRegister getDesignatorRegister() { return _designatorRegister; }
    public boolean getDevelopmentMode() { return _developmentMode; }

    public GeneralRegister getGeneralRegister(
        final int index
    ) throws MachineInterrupt {
        if (!GeneralRegisterSet.isAccessAllowed(index, _designatorRegister.getProcessorPrivilege(), false)) {
            throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.ReadAccessViolation, false);
        }
        return _generalRegisterSet.getRegister(index);
    }

    public IndicatorKeyRegister getIndicatorKeyRegister() { return _indicatorKeyRegister; }
    public MachineInterrupt getLastInterrupt() { return _lastInterrupt; }
    public StopReason getLatestStopReason() { return _latestStopReason; }
    public long getLatestStopDetail() { return _latestStopDetail; }
    public ProgramAddressRegister getProgramAddressRegister() { return _programAddressRegister; }
    public boolean getRunningFlag() { return _runningFlag; }

    public void loadActiveBaseTable(
        final ActiveBaseTableEntry[] source
    ) {
        for (int ax = 0; ax < source.length; ++ax) {
            if (ax < _activeBaseTableEntries.length) {
                _activeBaseTableEntries[ax] = source[ax];
            }
        }
    }

    public void loadActiveBaseTableEntry(
        final int index,
        final ActiveBaseTableEntry entry
    ) {
        _activeBaseTableEntries[index] = entry;
    }

    public void setBaseRegister(
        final int index,
        final BaseRegister baseRegister
    ) {
        _baseRegisters[index] = baseRegister;
    }

    public void setBroadcastInterruptEligibility(final boolean flag) { _broadcastInterruptEligibility = flag; }

    public void setGeneralRegister(
        final int index,
        final long value
    ) throws MachineInterrupt {
        if (!GeneralRegisterSet.isAccessAllowed(index, _designatorRegister.getProcessorPrivilege(), true)) {
            throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.WriteAccessViolation, false);
        }
        _generalRegisterSet.setRegister(index, value);
    }

    public void setJumpHistoryFullInterruptEnabled(final boolean flag) { _jumpHistoryFullInterruptEnabled = flag; }
    public void setProgramAddressRegister(final long value) { _programAddressRegister.setW(value); }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Private instance methods
    //  ----------------------------------------------------------------------------------------------------------------------------

    /*
     * //TODO:Move this comment somewhere more appropriate
     * When an interrupt is raised and the IP recognizes such, it saves interrupt information and other machine state
     * information on the ICS (Interrupt Control Stack) and the Jump History table.  The Program Address Register is
     * updated from the vector for the particular interrupt, and a new hard-held ASP (Activity State Packet) is built.
     * Then instruction processing proceeds per normal (albeit in interrupt handling state).
     * See the hardware doc for instructions as to what machine state information needs to be preserved.
     *
     * Interrupts are recognized and processed only at specific points during instruction execution.
     * If an instruction is interrupted mid-execution, the state of that instruction must be preserved on the ICS
     * so that it can be resumed when interrupt processing is complete, except during hardware check interrupts
     * (in which case, the sequence of instruction(s) leading to the fault will not be resumed/retried).
     *
     * Instructions which can be interrupted mid-execution include
     *      BIML
     *      BICL
     *      BIMT
     *      BT
     *      EXR
     *      BAO
     *      All search instructions
     * Additionally, the EX instruction may be interrupted between each lookup of the next indirectly-referenced
     * instruction (in the case where the EX instruction refers to another EX instruction, then to another, etc).
     * Also, basic-mode indirect addressing, which also may have lengthy or infinite indirection must be
     * interruptable during the U-field resolution.
     *
     *  Nonfault interrupts are always taken at the next interrupt point (unless classified as a pended
     * interrupt; see Table 5–1), which may be either a between instructions or mid-execution interrupt
     * point. Note: the processor is not required to take asynchronous, nonfault interrupts at the next
     * interrupt point as long as the interrupt is not "locked out" longer than one millisecond. When taken
     * at a between instruction interrupt point, machine state reflects instruction completion and
     * ICS.INF = 0. When taken at a mid-execution interrupt point, hardware must back up PAR.PC to
     * point to the interrupted instruction, or the address of the EX or EXR instruction which led to the
     * interrupted instruction, and the remainder of the pertinent machine state (see below) must reflect
     * progress to that point. In this case, ICS.INF := 1.
     *
     * Fault interrupts detected during an instruction with no mid-execution interrupt point cause hardware
     * to back up pertinent machine state (as described below) to reflect the environment in effect at the
     * start of the instruction. Fault interrupts detected during an instruction with mid-execution interrupt
     * points cause hardware to back up pertinent machine state to reflect the environment at the last
     * interrupt point (which may be either a between instruction or a mid-execution interrupt point).
     * ICS.INF := 1 for all fault interrupts except those caused by the fetching of an instruction.
     *
     * B26 describes the base and limits of the bank which comprises the Interrupt Control Stack.
     * EX1 contains the ICS frame size in X(i) and the fram pointer in X(m).  Frame size must be a multiple of 16.
     * ICS frame:
     *  +0      Program Address Register
     *  +1      Designator Register
     *  +2,S1       Short Status Field
     *  +2,S2-S5    Indicator Key Register
     *  +3      Quantum TImer
     *  +4      If INF=1 in the Indicator Key Register (see 2.2.5)
     *  +5      Interrupt Status Word 0
     *  +6      Interrupt Status Word 1
     *  +7 - ?  Reserved for software
     */

    private void baseBank(
        final int baseRegisterIndex,
        final int levelBDI
    ) throws MachineInterrupt {
        //1. Illegal Instruction and Invalid Interface Check: For LBU if F0.a specifies B0 or B1, an Illegal
        //Instruction interrupt occurs. For LXJ only, if Xa.IS = 3, an Addressing_Exception interrupt
        //occurs.

        //2. Prior L,BDI Fetch: For CALL only, the name of the Bank currently loaded into B0 is obtained
        //from hard-held L,BDI. For LXJ and LXJ/CALL only, the name of the Bank currently loaded into
        //the Base_Register to be altered is obtained from the appropriate ABT entry: For LBJ,
        //specified by Xa.BDR (BDR+12). For LDJ, B14 if DB31 = 0; B15 if DB31 = 1. For LIJ, B12 if
        //DB31 = 0; B13 if DB31 = 1.

        //3. Source L,BDI,Offset Determination: The Source L,BDI,Offset which is a Jump_to_Address for
        //transfers or a subsetting specification for loads is determined. Note: there is special
        //handling for Gates and for transfers to and from Basic_Mode. If read from storage or GRS,
        //a Reference_Violation interrupt (limits violation, Read access violation or GRS violation) may
        //occur.

        //4. Valid L,BDI Check: For all instructions, if the Source L,BDI is in the range 0,1 to 0,31, an
        //Addressing_Exception interrupt occurs (as specified in Section 10). If the processor is in the
        //interrupt sequence, the processor error halts setting an SCF readable "register" with an
        //indication that a software causable failure occurred.

        //5. Void Check: A void Bank may be loaded by certain instructions by specifying a Source L,BDI of
        //0,0. A void Bank is loaded by marking the B.V := 1. When a void Bank is to be loaded, as
        //indicated below, processing continues with step 10. An Addressing_Exception interrupt (as
        //specified in Section 10) occurs for the instructions below marked "Addressing_Exception".

        //6. Source BD Fetch: Source L,BDI has been determined to be > 0,31. For all instructions, the BD
        //described by source L,BDI is fetched as follows:
        //a. Bank_Descriptor_Table_Pointer (BDTP) selection is made by using B = 16+L.
        //b. An address is constructed by multiplying BDI by 8.
        //c. An Addressing_Exception interrupt is generated if the BD address formed is not
        //within the limits of the selected BDTP (except for the interrupt sequence, in which
        //case the processor error halts, setting an SCF readable 'register' with an indication
        //that a software causable failure occurred during the interrupt sequence). See Section
        //8 for special BD addressing rules.
        //d. The BD at the address BDI*8 relative to B16+L is fetched.

        //7. Source BD.Type Examination: The BD.Type is examined and various actions are taken
        //according to BD.Type and instruction. The following phrases are used in the instruction
        //breakdowns:
        //Addressing_Exception: With this BD.Type, an Addressing_Exception interrupt (as
        //specified in Table 10–1) occurs.
        //Indirect_BD_processing: The Source BD.Type = Indirect and a new BD, the
        //Indirected-to BD, is to be fetched. Base_Register is loaded
        //from the Indirected-to BD. Proceed with step 8.
        //Gate_BD_processing: The Source BD.Type = Gate and a Gate is to be processed.
        //Proceed with step 9.
        //Source_becomes_target: Target BD is the Source BD for loading the Base_Register.
        //Proceed with step 10.
        //Treated_as_void: Set: B.V := 1. Proceed with step 10.
        //Terminal_Addressing_Exception: With this BD.Type, a Terminal_Addressing_Exception
        //interrupt (as specified in Table 10–1) occurs. The
        //Terminal_Addressing_Exception is determined in this step
        //but reported in step 21 after determining the priorities of the
        //particular Terminal_Addressing_Exceptions to report.
        //Proceed with step 10.

        //8. Indirect BD: A Source BD.Type = Indirect has now been fetched for an instruction for which
        //an Indirect BD is valid (Addressing_Exception interrupt was not detected in step 7). If the
        //Indirect BD.G = 1, an Addressing_Exception interrupt occurs. If the Indirect L,BDI of the
        //Indirect BD is in the range 0,0 through 0,31, an Addressing_Exception interrupt occurs. The
        //priority of the above two interrupts is model_dependent.
        //Otherwise, another BD (the Indirected-to BD) is fetched, using the Indirect L,BDI of the
        //Indirect BD, as described in step 6, sub-steps a through d (except that any
        //Addressing_Exception interrupt generated is fatal). The Indirected-to BD.Type is examined
        //and various actions are taken according to Indirected-to BD.Type and instruction. The
        //following phrases are used in the instruction breakdowns:
        //Gate BD processing: The Indirected-to BD is a Gate BD and a Gate is to be processed.
        //Proceed with step 9.
        //Indirected-to BD
        //becomes target:
        //Indirected-to BD is Target BD. The Base_Register is loaded from the
        //Target BD. Proceed with step 10.
        //Treated as void: Set B.V := 1. Proceed with step 10.

        //9. Gate BD: A Source BD.Type = Gate has now been fetched for an instruction that can invoke
        //Gate processing. If the Gate BD.G = 1, an Addressing_Exception interrupt occurs. If Enter
        //access to the Gate Bank is denied (current Access_Key is checked against the Access_Lock
        //of the Gate BD to select either GAP or SAP), an Addressing_Exception interrupt occurs*.
        //Otherwise, the Gate is fetched as follows:
        //a. Source Offset is limits checked against the Gate BD; if a limits violation is detected an
        //Addressing_Exception interrupt occurs.
        //b. If either (model_dependent) an absolute boundary violation is detected on the Gate
        //address or the Xa.Offset does not specify an 8-word Offset [implementation must detect
        //invalid Offset one way or the other], an Addressing_Exception interrupt occurs†. See
        //Section 8 for special Gate addressing rules.
        //c. Source Offset is applied to the Base_Address of the Gate BD and the Gate is fetched
        //from storage (paging is invoked on this access).
        //d. The current Access_Key is checked for Enter access against the Access_Lock, GAP and
        //SAP of the Gate (the GAP and SAP fields of the Gate correspond to the BD.GAP.E and
        //BD.SAP.E); an Addressing_Exception interrupt occurs if access is denied. Thus, to use a
        //Gate, one must have Enter access to both the Gate Bank (via the Gate BD) and the
        //particular Gate.
        //e. If a GOTO or an LBJ with Xa.IS = 1 operation is being performed, an Addressing_Exception
        //interrupt occurs when the Gate.GI = 1 (GOTO_Inhibit), regardless of the Target BD.
        //f. If the Target L,BDI is in the range 0,0 to 0,31, an Addressing_Exception interrupt occurs*.
        //g. If the GateBD.LIB = 1 processing continues with step 9a.
        //h. The Designator Bits, Access_Key, Latent Parameters and B fields from the Gate must be
        //retained if enabled or applicable (see 3.1.3).
        //i. The Target BD is fetched as described in step 6, sub-steps a through d (except that any
        //Addressing_Exception interrupt generated is fatal).
        //j. The Target BD.Type is examined and if a BD.Type  Extended_Mode and
        //BD.Type  Basic_Mode, instruction results are Architecturally_Undefined (any
        //Addressing_Exceptions associated with the Source BD must be noted as
        //Terminal_Addressing_Exceptions for reporting in step 21). Otherwise, processing
        //continues with step 10. Note: the Target BD.Type determines the resulting
        //environment (Basic_Mode or Extended_Mode) and that step 21 does not check Enter
        //access in the Target BD on gated transfers.

        //9a. If the model does not support library gates, or if the library gate was not called in extended
        //mode (CALL or GOTO), or if the Library_Name is not defined, by that model, an
        //Addressing_exception interrupt occurs.
        //If the library gate was called by the CALL instruction an RCS entry is created prior to calling
        //the Library_Name. Once the Library has returned control to the instruction processor a
        //pseudo RTN operation is performed on the RCS entry. Note that the RCS may have been
        //manipulated by the Library call. If the pseudo RTN operation fails the instruction processor
        //will halt.

        //10. Base_Register Determination: The Target BD has now been determined or the Base_Register
        //is to be marked void. A determination is made of the Base_Register to be loaded. Also for
        //mixed-mode transfers, the Base_Register loaded with the Bank being exited is modified as
        //described in step 11.
        //Loads: LBU – Specified by F0.a (Ba).
        //LBE – Specified by F0.a (Ba+16).
        //LAE – Loads all of B1–B15.
        //EM to EM Transfers: Loads B0.
        //BM to BM Transfers: LXJ – For LBJ, specified by Xa.BDR (BDR+12). For LDJ, B14 if DB31 = 0; B15 if
        //DB31 = 1. For LIJ, B12 if DB31 = 0; B13 if DB31 = 1.
        //LXJ/RTN to BM – Specified by the RCS B-field (B+12).
        //EM to BM Transfers: GOTO to BM, CALL to BM – Nongated, loads B12; gated, specified by the Gate
        //B-field (B+12).
        //RTN to BM – Specified by the RCS B-field (B+12).
        //BM to EM Transfers: Loads B0.
        //User Return: Loads B0.
        //Interrupt Sequence: Loads B0.

        //11. Prior Bank Processing: On certain transfers, the Base_Register loaded with the Bank being
        //exited is modified depending on the BD.Type of transfer.
        //Loads: Not applicable.
        //EM to EM Transfers: Not applicable.
        //BM to BM Transfers: Not applicable.
        //EM to BM Transfers: GOTO to BM, CALL to BM, RTN to BM – B0.V := 1 and hard-held L,BDI := 0,0,
        //marking B0 as void.
        //BM to EM Transfers: LXJ/GOTO, LXJ/CALL – For LBJ, specified by Xa.BDR (BDR+12). For LDJ, B14 if
        //DB31 = 0; B15 if DB31 = 1. For LIJ, B12 if DB31 = 0; B13 if DB31 = 1. The
        //Basic_Mode Base_Register specified B.V := 1. The associated
        //ABT.L,BDI := 0,0; ABT.Offset is Architecturally_Undefined.
        //LXJ/RTN to EM – The Basic_Mode Base_Register B(RCS.B+12).V := 1. The
        //associated ABT.L,BDI := 0,0; ABT.Offset is Architecturally_Undefined.
        //User Return: Not applicable.
        //Interrupt Sequence: Not applicable.

        //12. RCS Write: On certain transfer instructions, certain activity state (at time of instruction
        //execution) is captured on the Return_Control_Stack.
        //A spurious RCS entry can be written by transfer instructions that do not require an RCS write
        //(for example, normal LXJ), provided no RCS Overflow condition exists. If an RCS Overflow
        //condition exists, the condition must be suppressed and no RCS write can occur.
        //EM to EM Transfers: GOTO, RTN – Not applicable.
        //CALL – The RCS frame is written with the following information:
        // RCS.Reentry_Point_Program_Address.L,BDI := Prior L,BDI (retained
        //in step 2).
        // RCS.Reentry_Point_Program_Address.Offset := PAR.PC + 1 (points
        //to instruction following CALL).
        // RCS.DB12-17 := current DB12–17 and
        //RCS.Access_Key := Indicator/Key_Register.Access_Key.
        // RCS.B := 0, RCS.Trap := 0, and RCS.Must_Be_Zero := 0.
        //BM to BM Transfers: Not applicable.
        //EM to BM Transfers:  GOTO to BM, RTN to BM – Not applicable.
        // CALL to BM – The RCS frame is written with the following
        //information:
        // RCS.Reentry_Point_Program_Address.L,BDI := prior L,BDI (retained
        //in step 2).
        // RCS.Reentry_Point_Program_Address.Offset := PAR.PC + 1 (points
        //to instruction following CALL to BM).
        // RCS.DB12-17 := current DB12–17 and
        //RCS.Access_Key := Indicator/Key_Register.Access_Key.
        // RCS.B := Gate.B or RCS.B := 0 if no Gate was processed.
        // RCS.Trap := 0 and RCS.Must_Be_Zero := 0.
        //BM to EM Transfers: LXJ/GOTO, LXJ/RTN to EM – Not applicable.
        //LXJ/CALL to EM – The RCS frame is written with the following
        //information:
        // RCS/Reentry_Point_Program_Address.L,BDI := prior L,BDI from the
        //selected Base_Registers ABT entry (retained in step 2).
        // RCS.Reentry_Point_Program_Address.Offset := PAR.PC + 1 (points
        //to instruction following LBJ/CALL).
        // RCS.DB12-17 := current DB12–17 and
        //RCS.Access_Key := Indicator/Key_Register.Access_Key.
        // Write BDR to be loaded on return to RCS.B. For LBJ, specified by
        //RCS.B := Xa.BDR (BDR + 12). For LDJ, RCS.B := B14 if DB31 = 0;
        //RCS.B := B15 if DB31 = 1. For LIJ, RCS.B := B12 if DB31 = 0;
        //RCS.B := B13 if DB31 = 1.
        // RCS.Trap := 0 and RCS.Must_Be_Zero := 0.
        //User Return: Not applicable.
        //Interrupt Sequence: Not applicable.

        //13. Xa Write: On normal LXJ only, Prior L,BDI is translated to E, LS, BDI as described in 4.6.3.1 and,
        //together with PAR.PC + 1 (points to instruction following the transfer instruction), is written to
        //Xa as follows:
        //Xa E BDR LS IS  BDI  PAR,PC+1
        //   0 1 2 3  4 5 6 17 18 35
        //BDR reflects the Base_Register that was loaded. Note: the Xa.IS := 0.
        //On CALL to BM only, Xa.IS := 2; the remaining bits are Architecturally_Undefined. The value of
        //DB17 in effect at instruction initiation determines whether User or Executive X11 is written.
        //X11 is then ready to be used as the Xa of an LBJ/RTN.

        //14. User X0 Write: On some transfer instructions, User X0 (regardless of the value of DB17) is
        //written to contain certain activity state (at time of instruction execution) as follows:
        //User X0
        //DB16  Zeros  Access Key
        //0     1  17  18      35
        //Loads: Not applicable.
        //EM to EM Transfers: GOTO, CALL – User X0 written.
        //RTN – Not applicable.
        //BM to BM Transfers: LXJ – User X0 written.
        //LXJ/RTN to BM – Not applicable.
        //EM to BM Transfers: GOTO to BM, CALL to BM – User X0 written.
        //RTN to BM – Not applicable.
        //BM to EM Transfers: LXJ/GOTO to EM, LXJ/CALL to EM – User X0 written.
        //LXJ/RTN to EM – Not applicable.
        //User Return: Not applicable.
        //Interrupt Sequence: Not applicable.

        //15. Gate Fields Transfer: On transfer instructions that process a Gate (executed step 9), certain
        //fields from the Gate are transferred as follows:
        //a. Load Designator Bits as appropriate. If Gate.DBI = 0,
        //Designator_Register.DB12-15 := Gate.DB12-15 and Designator_Register.DB17 := Gate.DB17.
        //b. Load Access_Key as appropriate. If Gate.AKI = 0, the hard-held
        //Indicator/Key_Register.Access_Key := Gate.Access_Key.
        //c. Load Latent Parameters as appropriate. If Gate.LP0I = 0, then if current DB17 = 0,
        //UR0 := Gate.Latent_Parameter_0 else ER0 := Gate.Latent_Parameter_0. If Gate.LP1I = 0,
        //then if current DB17 = 0, UR1 := Gate.Latent_Parameter_1 else
        //ER1 := Gate.Latent_Parameter_1. GRS selection (user or exec) is controlled by the current
        //value of DB17 (that is, Gate.DB17 if DBI = 0 or initial DB17 if DBI = 1).

        //16. Hard-held ASP Write: On certain transfer instructions, the hard-held ASP may be altered.
        //Note: the action listed below and the listed in step 15 are mutually exclusive; action listed
        //below is either on instructions which cannot invoke Gates or affects only DB16, which is
        //not altered in step 15.
        //Loads: Not applicable.
        //EM to EM Transfers: GOTO, CALL – Not applicable.
        //RTN – Hard-held Access_Key and DB12–17 are replaced by the corresponding
        //fields in the RCS.
        //BM to BM Transfers: LXJ – Not applicable.
        //LXJ/RTN to BM – Hard-held Access_Key and DB12–17 are replaced by the
        //corresponding fields in the RCS.
        //EM to BM Transfers: GOTO to BM, CALL to BM – DB16 := 1.
        //RTN to BM – Hard-held Access_Key and DB12–17 are replaced by the
        //corresponding fields in the RCS.
        //BM to EM Transfers: LXJ/GOTO to EM, LXJ/CALL to EM – DB16 := 0.
        //LXJ/RTN to EM – Hard-held Access_Key and DB12–17 are replaced by the
        //corresponding fields in the RCS.
        //User Return: Entire ASP (except the Short_Status_Field of the Indicator/Key_Register and
        //the Interrupt_Status_Words) is replaced with operand contents.
        //Interrupt Sequence: New hard-held ASP formed by hardware (see step 3 of 5.1.5).

        //17. Hard-held PAR.PC Update: For transfer instructions only, the 18-bit Offset determined in step
        //3 (or step 9 on a gated transfer) is written to the hard-held PAR.PC to be used to fetch the
        //first instruction at the jump target (unless an error is detected before instruction termination).
        //The jump target does not have to reside in the new Bank.

        //18. ABT Entry or Hard-held L,BDI Update: The ABT entry corresponding to the Base_Register to
        //be loaded or hard-held PAR.L,BDI is written to reflect the Bank selected in steps 3 through 9.
        //If B0 is to be loaded, hard-held PAR.L,BDI is written with the Target L,BDI.
        //Else, if a void Bank is to be loaded due to an L,BDI of 0,0 or due to an attempted LBU load of a
        //Basic_Mode Bank at PP > 1 without Enter access, ABT.L,BDI is written to 0,0 and the contents
        //of ABT.Offset are Architecturally_Undefined.
        //Else, the L,BDI portion of the ABT entry is written with the Target L,BDI. For loads,
        //ABT.Offset is written with the 18-bit Offset determined in step 3. For transfers when the
        //ABT is written, ABT.Offset := 0.

        //19. Base_Register Loading: When the LAE instruction generates a Class 9 interrupt according to
        //BD.Type errors, it does not complete this step of the algorithm.
        //The Base_Register selected in step 10 is loaded with the BD information (or the B.V := 1) as
        //determined in steps 3 through 9.
        //For nonvoid load instructions, subsetting occurs if the 18-bit Offset ≠ 0 (as determined in step
        //3); see 4.6.6.
        //Architecturally_Undefined: For nonvoid transfer instructions, if B.S =1 then B.Lower_Limit
        //and B.Upper_Limit is undefined.

        //20. DB31 Toggle: On transfers to Basic_Mode, DB31 is toggled as described in 4.4.2.3.

        //21. Exception Checks: The BD loaded in step 19 is checked for certain exception conditions. If a
        //void bank was loaded, these checks are not made. If one or more of these exceptions are
        //present a Terminal_Addressing_Exception interrupt is generated and the Base_Register
        //loaded in step 19 remains loaded (note, however, that B0 is reloaded during the interrupt
        //sequence.
        //Architecturally_Undefined: If the void resulted from a calculation of a negative
        //Upper_Limit while subsetting, it is undefined whether these checks are made.
        //Code Exception Meaning
        //G General. BD.G = 1.
        //E Entry. An attempt was made to do a nongated transfer to an Extended_Mode Bank for which
        //  Enter access is denied.
        //V Validated Entry. An attempt was made to do a nongated transfer to a Basic_Mode Bank for
        //  which Enter access is denied, at a Relative_Address unequal to the starting Relative_Address of
        //  the Bank; that is, the 18-bit Offset (Jump_to_Address) is not equal to the Target BD’s
        //  Lower_Limit concatenated with nine trailing zeros (this check need not take into account the
        //  BD.S, since instructions cannot be fetched from Large_Banks). This check, combined with the
        //  following one, provides the functional equivalent of the Validated Entry Point mechanism of
        //  previous architectures; that is, a GAP.R = 1 or SAP.R = 1 for a Basic_Mode Bank implies Validated
        //  Entry Point.
        //  Software note: Banks that are intended to be protected by the Validated Entry Point
        //  mechanism must have both GAP.E = 0 and SAP.E = 0 in order to guarantee that the Bank
        //  cannot be accessed by the LBU instruction.
        //S Selection of Base_Register. An attempt was made to do either a gated transfer, or a nongated
        //  transfer for which Enter access is denied, to a Basic_Mode Bank, but new PAR.PC (U or the
        //  Offset from the Gate) does not select (using Basic_Mode Base_Register selection; see 4.4.6) the
        //  Base_Register being modified.
        //T RCS Trap. RCS.Trap := 1.

        //Notes:
        // For those sequences which check multiple exception conditions, priority order checking
        //is required as specified in Class 10 interrupts; see 5.2.6.
        // No Read or Write access checks are performed on the Target Bank when it is being
        //loaded into a Base_Register; such checks are made on every read or write attempt using
        //that Base_Register. Conversely, Enter access is checked only at base loading; thus, the
        //GAP.E and SAP.E are not provided in the Base_Register.
        // BD.Type compared to the particular Base_Register Manipulation algorithm (from step 7, 8,
        //or 9 of the algorithm where the checks were made).
        //
        //Instruction Breakdown Exception Check – G
        //Loads: LBU, LBE – BD.G checked. BD.G is not checked for
        //Queue_Bank_Repository, and it is Architecturally_Undefined whether BD.G
        //is checked for Queue_Bank since the Queue_Bank must be active to be
        //visible in a BDT in order to be loaded.
        //LAE – BD.G not checked.
        //EM to EM Transfers: BD.G checked.
        //BM to BM Transfers: BD.G checked.
        //EM to BM Transfers: BD.G checked.
        //BM to EM Transfers: BD.G checked.
        //User Return: BD.G not checked.
        //Interrupt Sequence: BD.G not checked.
        //
        //Instruction Breakdown Exception Check – E
        //Loads: Not checked.
        //EM to EM Transfers: GOTO, CALL – Checked unless a Gate is invoked.
        //RTN – Not checked.
        //BM to BM Transfers: LXJ – Enter access to nongated Extended_Mode Bank on LXJ is accorded
        //special treatment. No Addressing_Exception occurs; rather, when Enter
        //access is denied, no mixed-mode transfer occurs; see 4.6.3.3.
        //LXJ/RTN to BM – Not checked.
        //EM to BM Transfers: Not checked.
        //BM to EM Transfers: LXJ/GOTO to EM, LXJ/CALL to EM – See note for LXJ above.
        //LXJ/RTN to EM – Not checked.
        //User Return: Not checked.
        //Interrupt Sequence: Not checked.
        //
        //Instruction Breakdown Exception Check – V
        //Loads: Not checked.
        //EM to EM Transfers: Not Checked.
        //BM to BM Transfers: LXJ – Checked when nongated, Target BD.Type = Basic_Mode and Enter
        //access is denied.
        //LXJ/RTN to BM – Not checked.
        //EM to BM Transfers: GOTO to BM, CALL to BM – Checked when nongated and Enter access is
        //denied.
        //RTN to BM – Not checked.
        //BM to EM Transfers: Not checked.
        //User Return: Not checked.
        //Interrupt Sequence: Not checked.
        //
        //Instruction Breakdown Exception Check – S
        //Loads: Not checked.
        //EM to EM Transfers: Not checked.
        //BM to BM Transfers: LXJ – Checked when a) gated; or b) nongated, Target BD.Type =
        //Basic_Mode and Enter access is denied.
        //LXJ/RTN to BM – Not checked.
        //EM to BM Transfers: GOTO to BM, CALL to BM – Checked when a) gated; or b) nongated and
        //Enter access is denied.
        //RTN to BM – Not checked.
        //BM to EM Transfers: Not checked.
        //User Return: Not checked.
        //Interrupt Sequence: Not checked.
        //
        //Instruction Breakdown Exception Check – T
        //Loads: Not checked.
        //EM to EM Transfers: GOTO, CALL – Not checked.
        //RTN  Checked.
        //BM to BM Transfers: LXJ – Not checked.
        //LXJ/RTN to BM  Checked.
        //EM to BM Transfers: Not checked.
        //BM to EM Transfers: LXJ/GOTO to EM, LXJ/CALL to EM  Not checked.
        //LXJ/RTN to EM  Checked
        //User Return: Not checked.
        //Interrupt Sequence: Not checked.
    }

    /**
     * Calculates the raw relative address (the U) for the current instruction.
     * Does NOT increment any x registers, even if their content contributes to the result.
     * @param offset For multiple transfer instructions which need to calculate U for each transfer,
     *                  this value increments from zero upward by one.
     * @return relative address for the current instruction
     */
    //TODO may not need offset parameter...
    private int calculateRelativeAddressForGRSOrStorage(
        final int offset
    ) {
        IndexRegister xReg = null;
        int xx = (int)_currentInstruction.getX();
        if (xx != 0) {
            xReg = getExecOrUserXRegister(xx);
        }

        long addend1;
        long addend2 = 0;
        if (_designatorRegister.getBasicModeEnabled()) {
            addend1 = _currentInstruction.getU();
            if (xReg != null) {
                addend2 = xReg.getSignedXM();
            }
        } else {
            addend1 = _currentInstruction.getD();
            if (xReg != null) {
                if (_designatorRegister.getExecutive24BitIndexingEnabled()
                        && (_designatorRegister.getProcessorPrivilege() < 2)) {
                    //  Exec 24-bit indexing is requested
                    addend2 = xReg.getSignedXM24();
                } else {
                    addend2 = xReg.getSignedXM();
                }
            }
        }

        long result = OnesComplement.add36Simple(addend1, addend2);
        if (offset != 0) {
            result = OnesComplement.add36Simple(result, offset);
        }

        return (int)result;
    }

    /**
     * Calculates the raw relative address (the U) for the current instruction.
     * Does NOT increment any x registers, even if their content contributes to the result.
     * @param offset For multiple transfer instructions which need to calculate U for each transfer,
     *                  this value increments from zero upward by one.
     * @return relative address for the current instruction
     */
    private int calculateRelativeAddressForJump(
        final int offset
    ) {
        IndexRegister xReg = null;
        int xx = (int)_currentInstruction.getX();
        if (xx != 0) {
            xReg = getExecOrUserXRegister(xx);
        }

        long addend1;
        long addend2 = 0;
        if (_designatorRegister.getBasicModeEnabled()) {
            addend1 = _currentInstruction.getU();
            if (xReg != null) {
                addend2 = xReg.getSignedXM();
            }
        } else {
            addend1 = _currentInstruction.getU();
            if (xReg != null) {
                if (_designatorRegister.getExecutive24BitIndexingEnabled()
                    && (_designatorRegister.getProcessorPrivilege() < 2)) {
                    //  Exec 24-bit indexing is requested
                    addend2 = xReg.getSignedXM24();
                } else {
                    addend2 = xReg.getSignedXM();
                }
            }
        }

        long result = OnesComplement.add36Simple(addend1, addend2);
        if (offset != 0) {
            result = OnesComplement.add36Simple(result, offset);
        }

        return (int) result;
    }

    /**
     * Checks the given absolute address and comparison type against the breakpoint register to see whether
     * we should take a breakpoint.  Updates IKR appropriately.
     * @param comparison comparison type
     * @param absoluteAddress absolute address to be compared
     */
    private void checkBreakpoint(
        final BreakpointComparison comparison,
        final AbsoluteAddress absoluteAddress
    ) {
        if (_breakpointAddress.equals(absoluteAddress)
                && (((comparison == BreakpointComparison.Fetch) && _breakpointRegister.getFetchFlag())
                    || ((comparison == BreakpointComparison.Read) && _breakpointRegister.getReadFlag())
                    || ((comparison == BreakpointComparison.Write) && _breakpointRegister.getWriteFlag()))) {
            //TODO Per doc, 2.4.1.2 Breakpoint_Register - we need to halt if Halt Enable is set
            //      which means Stop Right Now... how do we do that for all callers of this code?
            _indicatorKeyRegister.setBreakpointRegisterMatchCondition(true);
        }
    }

    /**
     * If an interrupt is pending, handle it.
     * If not, check certain conditions to see if one of several certain interrupt classes needs to be raised.
     * @return true if we did something useful, else false
     * @throws MachineInterrupt if we need to cause an interrupt to be raised
     */
    private boolean checkPendingInterrupts(
    ) throws MachineInterrupt {
        //  Is there an interrupt pending?  If so, handle it
        if (_pendingInterrupt != null) {
            handleInterrupt();
            return true;
        }

        //  Are there any pending conditions which need to be turned into interrupts?
        if (_indicatorKeyRegister.getBreakpointRegisterMatchCondition() && !_midInstructionInterruptPoint) {
            if (_breakpointRegister.getHaltFlag()) {
                stop(StopReason.Breakpoint, 0);
                return true;
            } else {
                throw new BreakpointInterrupt();
            }
        }

        if (_quantumTimer.isNegative() && _designatorRegister.getQuantumTimerEnabled()) {
            throw new QuantumTimerInterrupt();
        }

        if (_indicatorKeyRegister.getSoftwareBreak() && !_midInstructionInterruptPoint) {
            throw new SoftwareBreakInterrupt();
        }

        if (_jumpHistoryThresholdReached && _jumpHistoryFullInterruptEnabled && !_midInstructionInterruptPoint) {
            throw new JumpHistoryFullInterrupt();
        }

        return false;
    }

    /**
     * Creates a new entry in the conditionalJump history table.
     * If we cross the interrupt threshold, set the threshold-reached flag.
     * @param value absolute address to be placed into the conditionalJump history table
     */
    private void createJumpHistoryTableEntry(
        final long value
    ) {
        _jumpHistoryTable[_jumpHistoryTableNext].setW(value);

        if (_jumpHistoryTableNext > JUMP_HISTORY_TABLE_THRESHOLD ) {
            _jumpHistoryThresholdReached = true;
        }

        if (_jumpHistoryTableNext == JUMP_HISTORY_TABLE_SIZE ) {
            _jumpHistoryTableNext = 0;
        }
    }

    /**
     * Starts or continues the process of executing the instruction in _currentInstruction.
     * Don't call this if IKR.INF is not set.
     * @throws MachineInterrupt if an interrupt needs to be raised
     * @throws UnresolvedAddressException if a basic mode indirect address is not entirely resolved
     */
    protected void executeInstruction(
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        //  Call the function handler, then keep INF (instruction in F0) true if we have not completed
        //  the instruction (MidInstIntPt == true), or false if we are not (MidInstIntPt == false).
        //  It is up to the function handler to:
        //      * set or clear m_MidInstructionInterruptPoint as appropriate
        //      * properly store instruction mid-point state if it returns mid-instruction
        //      * detect and restore instruction mid-point state if it is subsequently called
        //          after returning in mid-point state.
        FunctionHandler handler = FunctionTable.lookup(_currentInstruction, _designatorRegister.getBasicModeEnabled());
        if (handler == null) {
            _midInstructionInterruptPoint = false;
            _indicatorKeyRegister.setInstructionInF0(false);
            throw new InvalidInstructionInterrupt(InvalidInstructionInterrupt.Reason.UndefinedFunctionCode);
        }

        handler.handle(this, _currentInstruction);
        _indicatorKeyRegister.setInstructionInF0(_midInstructionInterruptPoint);
        if (!_midInstructionInterruptPoint) {
            //  instruction is done - clear storage locks
            synchronized(_storageLocks) {
                _storageLocks.get(this).clear();
            }
        }
    }

    /**
     * Fetches the next instruction based on the current program address register,
     * and places it in the current instruction register.
     * Basic mode:
     *  We cannot fetch from a large bank (EX, EXR can refer to an instruction in a large bank, but cannot be in one)
     *  We must check upper and lower limits unless the program counter is 0777777 (why? I don't know...)
     *  Since we use findBasicModeBank(), we are assured of this check automatically.
     *  We need to determine which base register we execute from, and read permission is needed for the corresponding bank.
     * Extended mode:
     *  We cannot fetch from a large bank (EX, EXR can refer to an instruction in a large bank, but cannot be in one)
     *  We must check upper and lower limits unless the program counter is 0777777 (why? I don't know...)
     *  Access is not checked, as GAP and SAP for enter access are applied at the time the bank is based on B0,
     *  and if that passes, GAP and SAP read access are automatically set true.
     *  EX and EXR targets still require read-access checks.
     * @throws MachineInterrupt if an interrupt needs to be raised
     */
    private void fetchInstruction(
    ) throws MachineInterrupt {
        _midInstructionInterruptPoint = false;
        boolean basicMode = _designatorRegister.getBasicModeEnabled();
        int programCounter = _programAddressRegister.getProgramCounter();

        BaseRegister bReg;
        if (basicMode) {
            int baseRegisterIndex = findBasicModeBank(programCounter, true);
            if (baseRegisterIndex == 0) {
                throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.StorageLimitsViolation, true);
            }

            bReg = _baseRegisters[baseRegisterIndex];
            if (!isReadAllowed(bReg)) {
                throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.ReadAccessViolation, true);
            }
        } else {
            bReg = _baseRegisters[0];
            bReg.checkAccessLimits(programCounter, true, false, false, _indicatorKeyRegister.getAccessInfo());
        }

        if (bReg._voidFlag || bReg._largeSizeFlag) {
            throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.StorageLimitsViolation, true);
        }

        int pcOffset = programCounter - bReg._lowerLimitNormalized;
        _currentInstruction.setW(bReg._storage.get(pcOffset));
        _indicatorKeyRegister.setInstructionInF0(true);
    }

    /**
     * Locates the index of the base register which represents the bank which contains the given relative address.
     * Does appropriate limits checking.  Delegates to the appropriate basic or extended mode implementation.
     * @param relativeAddress relative address to be considered
     * @param updateDesignatorRegister if true and if we are in basic mode, we update the basic mode bank selection bit
     *                                 in the designator register if necessary
     * @return base register index
     * @throws MachineInterrupt if any interrupt needs to be raised.
     *                          In this case, the instruction is incomplete and should be retried if appropriate.
     * @throws UnresolvedAddressException if address resolution is unfinished (such as can happen in Basic Mode with
     *                                    Indirect Addressing).  In this case, caller should call back here again after
     *                                    checking for any pending interrupts.
     */
    private int findBaseRegisterIndex(
        final int relativeAddress,
        final boolean updateDesignatorRegister
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        if (_designatorRegister.getBasicModeEnabled()) {
            //  Find the bank containing the current offset.
            //  We don't need to check for storage limits, since this is done for us by findBasicModeBank() in terms of
            //  returning a zero.
            int brIndex = findBasicModeBank(relativeAddress, updateDesignatorRegister);
            if (brIndex == 0) {
                throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.StorageLimitsViolation, false);
            }

            //  Are we doing indirect addressing?
            if (_currentInstruction.getI() != 0) {
                //  Increment the X register (if any) indicated by F0 (if H bit is set, of course)
                incrementIndexRegisterInF0();
                BaseRegister br = _baseRegisters[brIndex];

                //  Ensure we can read from the selected bank
                if (!isReadAllowed(br)) {
                    throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.ReadAccessViolation, false);
                }
                br.checkAccessLimits(relativeAddress, false, true, false, _indicatorKeyRegister.getAccessInfo());

                //  Get xhiu fields from the referenced word, and place them into _currentInstruction,
                //  then throw UnresolvedAddressException so the caller knows we're not done here.
                int wx = relativeAddress - br._lowerLimitNormalized;
                _currentInstruction.setXHIU(br._storage.get(wx));
                throw new UnresolvedAddressException();
            }

            //  We're at our final destination
            return brIndex;
        } else {
            return getEffectiveBaseRegisterIndex();
        }
    }

    /**
     * Given a relative address, we determine which (if any) of the basic mode banks based on BDR12-15
     * are to be selected for that address.
     * We do NOT evaluate whether the bank has any particular permissions, or whether we have any access thereto.
     * @param relativeAddress relative address for which we search for a containing bank
     * @param updateDB31 set true to update DB31 if we cross primary/secondary bank pairs
     * @return the bank register index for the bank which contains the given relative address if found,
     *          else zero if the address is not within any based bank limits.
     */
    private int findBasicModeBank(
        final int relativeAddress,
        final boolean updateDB31
    ) {
        boolean db31Flag = _designatorRegister.getBasicModeBaseRegisterSelection();
        int[] table = db31Flag ? BASE_REGISTER_CANDIDATES_TRUE : BASE_REGISTER_CANDIDATES_FALSE;

        for (int tx = 0; tx < 4; ++tx) {
            //  See IP PRM 4.4.5 - select the base register from the selection table.
            //  If the bank is void, skip it.
            //  If the program counter is outside of the bank limits, skip it.
            //  Otherwise, we found the BDR we want to use.
            BaseRegister bReg = _baseRegisters[table[tx]];
            if (isWithinLimits(bReg, relativeAddress)) {
                if (updateDB31 && (tx >= 2)) {
                    //  address is found in a secondary bank, so we need to flip DB31
                    _designatorRegister.setBasicModeBaseRegisterSelection(!db31Flag);
                }

                return table[tx];
            }
        }

        return 0;
    }

    /**
     * Determines the base register to be used for an extended mode instruction,
     * using the designator bit to indicate whether to use exec or user banks,
     * and whether we are using the I bit to extend the B field.
     * (Exec base registers are B16-B31).
     * @return base register index
     */
    private int getEffectiveBaseRegisterIndex(
    ) {
        //  If PP < 2, we use the i-bit and the b-field to select the base registers from B0 to B31.
        //  For PP >= 2, we only use the b-field, to select base registers from B0 to B15 (See IP PRM 4.3.7).
        if (_designatorRegister.getProcessorPrivilege() < 2) {
            return (int)_currentInstruction.getIB();
        } else {
            return (int)_currentInstruction.getB();
        }
    }

    /**
     * Retrieves the AccessPermissions object applicable for the bank described by the given baseRegister,
     * within the context of our current key/ring.
     * @param baseRegister base register of interest
     * @return access permissions object
     */
    private AccessPermissions getEffectivePermissions(
        final BaseRegister baseRegister
    ) {
        AccessInfo tempInfo = new AccessInfo(_indicatorKeyRegister.getAccessKey());

        // If we are at a more-privileged ring than the base register's ring, use the base register's special access permissions.
        if (tempInfo._ring < baseRegister._accessLock._ring) {
            return baseRegister._specialAccessPermissions;
        }

        // If we are in the same domain as the base register, again, use the special access permissions.
        if (tempInfo._domain == baseRegister._accessLock._domain) {
            return baseRegister._specialAccessPermissions;
        }

        // Otherwise, use the general access permissions.
        return baseRegister._generalAccessPermissions;
    }

    /**
     * Retrieves the GRS index of the exec or user register indicated by the register index...
     * e.g., registerIndex == 0 returns the GRS index for either R0 or ER0, depending on the designator register.
     * @param registerIndex R register index of interest
     * @return GRS index
     */
    private int getExecOrUserRRegisterIndex(
        final int registerIndex
    ) {
        return registerIndex + (_designatorRegister.getExecRegisterSetSelected() ? GeneralRegisterSet.ER0 : GeneralRegisterSet.R0);
    }

    /**
     * Retrieves the GRS index of the exec or user register indicated by the register index...
     * e.g., registerIndex == 0 returns the GRS index for either X0 or EX0, depending on the designator register.
     * @param registerIndex X register index of interest
     * @return GRS index
     */
    private int getExecOrUserXRegisterIndex(
        final int registerIndex
    ) {
        return registerIndex + (_designatorRegister.getExecRegisterSetSelected() ? GeneralRegisterSet.EX0 : GeneralRegisterSet.X0);
    }

    /**
     * Handles the current pending interrupt.  Do not call if no interrupt is pending.
     * @throws MachineInterrupt if some other interrupt needs to be raised
     */
    private void handleInterrupt(
    ) throws MachineInterrupt {
        // Get pending interrupt, save it to lastInterrupt, and clear pending.
        MachineInterrupt interrupt = _pendingInterrupt;
        _pendingInterrupt = null;
        _lastInterrupt = interrupt;

        // Are deferrable interrupts allowed?  If not, ignore the interrupt
        if (!_designatorRegister.getDeferrableInterruptEnabled()
            && (interrupt.getDeferrability() == MachineInterrupt.Deferrability.Deferrable)) {
            return;
        }

        //TODO If the Reset Indicator is set and this is a non-initial exigent interrupt, then error halt and set an
        //      SCF readable “register” to indicate that a Reset failure occurred.

        // Update interrupt-specific portions of the IKR
        _indicatorKeyRegister.setShortStatusField(interrupt.getShortStatusField());
        _indicatorKeyRegister.setInterruptClassField(interrupt.getInterruptClass().getCode());

        // Make sure the interrupt control stack base register is valid
        if (_baseRegisters[ICS_BASE_REGISTER]._voidFlag) {
            stop(StopReason.ICSBaseRegisterInvalid, 0);
            return;
        }

        // Acquire a stack frame, and verify limits
        IndexRegister icsXReg = (IndexRegister)_generalRegisterSet.getRegister(ICS_INDEX_REGISTER);
        icsXReg.decrementModifier18();
        long stackOffset = icsXReg.getH2();
        long stackFrameSize = icsXReg.getXI();
        long stackFrameLimit = stackOffset + stackFrameSize;
        if ((stackFrameLimit - 1 > _baseRegisters[ICS_BASE_REGISTER]._upperLimitNormalized)
            || (stackOffset < _baseRegisters[ICS_BASE_REGISTER]._lowerLimitNormalized)) {
            stop(StopReason.ICSOverflow, 0);
            return;
        }

        // Populate the stack frame in storage.
        ArraySlice icsStorage = _baseRegisters[ICS_BASE_REGISTER]._storage;
        if (stackFrameLimit > icsStorage.getSize()) {
            stop(StopReason.ICSBaseRegisterInvalid, 0);
            return;
        }

        int sx = (int)stackOffset;
        icsStorage.set(sx, _programAddressRegister.getW());
        icsStorage.set(sx + 1, _designatorRegister.getW());
        icsStorage.set(sx + 2, _indicatorKeyRegister.getW());
        icsStorage.set(sx + 3, _quantumTimer.getW());
        icsStorage.set(sx + 4, interrupt.getInterruptStatusWord0().getW());
        icsStorage.set(sx + 5, interrupt.getInterruptStatusWord1().getW());

        //TODO other stuff which needs to be preserved - IP PRM 5.1.3
        //      e.g., results of stuff that we figure out prior to generating U in Basic Mode maybe?
        //      or does it hurt anything to just regenerate that?  We /would/ need the following two lines...
        //pStack[6].setS1( m_PreservedProgramAddressRegisterValid ? 1 : 0 );
        //pStack[7].setValue( m_PreservedProgramAddressRegister.getW() );

        // Create conditionalJump history table entry
        createJumpHistoryTableEntry(_programAddressRegister.getW());

        // The bank described by B16 begins with 64 contiguous words, indexed by interrupt class (of which there are 64).
        // Each word is a Program Address Register word, containing the L,BDI,Offset of the interrupt handling routine
        // Make sure B16 is valid before dereferencing through it.
        if (_baseRegisters[L0_BDT_BASE_REGISTER]._voidFlag) {
            stop(StopReason.L0BaseRegisterInvalid, 0);
            return;
        }

        //  intStorage points to level 0 BDT, the first 64 words of which comprise the interrupt vectors.
        //  intOffset is the offset from the start of the BDT, to the vector we're interested in.
        //  PAR will be set to L,BDI,Address of the appropriate interrupt handler.
        //  Note that the interrupt handler code bank is NOT YET based on B0...
        ArraySlice intStorage = _baseRegisters[L0_BDT_BASE_REGISTER]._storage;
        int intOffset = interrupt.getInterruptClass().getCode();
        if (intOffset >= icsStorage.getSize()) {
            stop(StopReason.InterruptHandlerOffsetOutOfRange, 0);
            return;
        }

        _programAddressRegister.setW(intStorage.get(intOffset));

        // Set designator register per IP PRM 5.1.5
        //  We'll set/clear Basic Mode later once we've got the interrupt handler bank
        boolean fhip = _designatorRegister.getFaultHandlingInProgress();
        _designatorRegister.clear();
        _designatorRegister.setExecRegisterSetSelected(true);
        _designatorRegister.setArithmeticExceptionEnabled(true);
        _designatorRegister.setFaultHandlingInProgress(fhip);

        if (interrupt.getInterruptClass() == MachineInterrupt.InterruptClass.HardwareCheck) {
            if (fhip) {
                stop(StopReason.InterruptHandlerHardwareFailure, 0);
                return;
            }
            _designatorRegister.setFaultHandlingInProgress(true);
        }

        // Clear the IKR and F0
        _indicatorKeyRegister.clear();
        _currentInstruction.clear();

        // Base the PAR-indicated interrupt handler bank on B0
        //TODO WE should use standard bank-manipulation algorithm here - see hardware manual 4.6.4
        byte ihBankLevel = (byte)_programAddressRegister.getLevel();
        short ihBankDescriptorIndex = (short)_programAddressRegister.getBankDescriptorIndex();
        if ((ihBankLevel == 0) && (ihBankDescriptorIndex < 32)) {
            stop(StopReason.InterruptHandlerInvalidLevelBDI, 0);
            return;
        }

        //  Retrieve a BankDescriptor object, ensure the bank type is acceptable, and base the bank.
        BankDescriptor bankDescriptor = findBankDescriptor(ihBankLevel, ihBankDescriptorIndex);
        BankDescriptor.BankType ihBankType = bankDescriptor.getBankType();
        if ((ihBankType != BankDescriptor.BankType.ExtendedMode) && (ihBankType != BankDescriptor.BankType.BasicMode)) {
            stop(StopReason.InterruptHandlerInvalidBankType, 0);
            return;
        }

        _baseRegisters[0] = new BaseRegister(bankDescriptor);
        _designatorRegister.setBasicModeBaseRegisterSelection(ihBankType == BankDescriptor.BankType.BasicMode);//TODO is this right?
    }

    /**
     * Checks a base register to see if we can read from it, given our current key/ring
     * @param baseRegister register of interest
     * @return true if we have read permission for the bank based on the given register
     */
    private boolean isReadAllowed(
        final BaseRegister baseRegister
    ) {
        return getEffectivePermissions(baseRegister)._read;
    }

    /**
     * Indicates whether the given offset is within the addressing limits of the bank based on the given register.
     * If the bank is void, then the offset is clearly not within limits.
     * @param baseRegister register of interest
     * @param offset address offset
     * @return true if the address is within the limits of the bank based on the given register
     */
    private boolean isWithinLimits(
        final BaseRegister baseRegister,
        final int offset
    ) {
        return !baseRegister._voidFlag
               && (offset >= baseRegister._lowerLimitNormalized)
               && (offset <= baseRegister._upperLimitNormalized);
    }

    /**
     * If no other interrupt is pending, or the new interrupt is of a higher priority,
     * set the new interrupt as the pending interrupt.  Any lower-priority interrupt is dropped or ignored.
     * @param interrupt interrupt of interest
     */
    private void raiseInterrupt(
        final MachineInterrupt interrupt
    ) {
        if ((_pendingInterrupt == null)
            || (interrupt.getInterruptClass().getCode() < _pendingInterrupt.getInterruptClass().getCode())) {
            _pendingInterrupt = interrupt;
        }
    }

    /**
     * Set a storage lock for the given absolute address.
     * If this IP already has locks, we die horribly - this is how we avoid internal deadlocks
     * If the address is already locked by any other IP, then we wait until it is not.
     * Then we lock it to this IP.
     * NOTE: All storage locks are cleared automatically at the conclusion of processing an instruction.
     * @param absAddress absolute address of interest
     */
    private void setStorageLock(
        final AbsoluteAddress absAddress
    ) {
        synchronized(_storageLocks) {
            assert(_storageLocks.get(this).isEmpty());
        }

        boolean done = false;
        while (!done) {
            synchronized(_storageLocks) {
                boolean okay = true;
                Iterator<Map.Entry<InstructionProcessor, HashSet<AbsoluteAddress>>> it = _storageLocks.entrySet().iterator();
                while (okay && it.hasNext()) {
                    Map.Entry<InstructionProcessor, HashSet<AbsoluteAddress>> pair = it.next();
                    InstructionProcessor ip = pair.getKey();
                    HashSet<AbsoluteAddress> lockedAddresses = pair.getValue();
                    if (ip != this) {
                        if (lockedAddresses.contains(absAddress)) {
                            okay = false;
                            break;
                        }
                    }
                }

                if (okay) {
                    _storageLocks.get(this).add(absAddress);
                    done = true;
                }
            }

            if (!done) {
                Thread.yield();
            }
        }
    }

    /**
     * As above, but for multiple addresses.
     * NOTE: All storage locks are cleared automatically at the conclusion of processing an instruction.
     * @param absAddresses array of addresses
     */
    private void setStorageLocks(
        final AbsoluteAddress[] absAddresses
    ) {
        synchronized(_storageLocks) {
            assert(_storageLocks.get(this).isEmpty());
        }

        boolean done = false;
        while (!done) {
            synchronized(_storageLocks) {
                boolean okay = true;
                Iterator<Map.Entry<InstructionProcessor, HashSet<AbsoluteAddress>>> it = _storageLocks.entrySet().iterator();
                while (okay && it.hasNext()) {
                    Map.Entry<InstructionProcessor, HashSet<AbsoluteAddress>> pair = it.next();
                    InstructionProcessor ip = pair.getKey();
                    HashSet<AbsoluteAddress> lockedAddresses = pair.getValue();
                    if (ip != this) {
                        for (AbsoluteAddress checkAddress : absAddresses) {
                            if (lockedAddresses.contains(checkAddress)) {
                                okay = false;
                                break;
                            }
                        }
                    }
                }

                if (okay) {
                    for (AbsoluteAddress absAddr : absAddresses) {
                        _storageLocks.get(this).add(absAddr);
                    }
                    done = true;
                }
            }

            if (!done) {
                Thread.yield();
            }
        }
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Async thread entry point
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * Entry point for the async Worker part of this object
     */
    @Override
    public void run(
    ) {
        LOGGER.info(String.format("InstructionProcessor worker %s Starting", getName()));
        synchronized(_storageLocks) {
            _storageLocks.put(this, new HashSet<AbsoluteAddress>());
        }

        while (!_workerTerminate) {
            // If the virtual processor is not running, then the thread does nothing other than sleep slowly
            if (!_runningFlag) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                }
            } else {
                //  Deal with pending interrupts, or conditions which will create a new pending interrupt.
                boolean somethingDone = false;
                try {
                    //  check for pending interrupts
                    somethingDone = checkPendingInterrupts();

                    //  If we don't have an instruction in F0, fetch one.
                    if (!somethingDone && !_indicatorKeyRegister.getInstructionInF0()) {
                        fetchInstruction();
                        somethingDone = true;
                    }

                    //  Execute the instruction in F0.
                    if (!somethingDone) {
                        _midInstructionInterruptPoint = false;
                        try {
                            executeInstruction();
                        } catch (UnresolvedAddressException ex) {
                            //  This is not surprising - can happen for basic mode indirect addressing.
                            //  Update the quantum timer so we can (eventually) interrupt a long or infinite sequence.
                            _midInstructionInterruptPoint = true;
                            if (_designatorRegister.getQuantumTimerEnabled()) {
                                _quantumTimer.add(Word36.NEGATIVE_ONE.getW());
                            }
                        }

                        if (!_midInstructionInterruptPoint) {
                            // Instruction is complete.  Maybe increment PAR.PC
                            if (_preventProgramCounterIncrement) {
                                _preventProgramCounterIncrement = false;
                            } else {
                                _programAddressRegister.setProgramCounter(_programAddressRegister.getProgramCounter() + 1);
                            }

                            //  Update IKR and (maybe) the quantum timer
                            _indicatorKeyRegister.setInstructionInF0(false);
                            if (_designatorRegister.getQuantumTimerEnabled()) {
                                _quantumTimer.add(OnesComplement.negate36(_currentInstructionHandler.getQuantumTimerCharge()));
                            }

                            // Should we stop, given that we've completed an instruction?
                            if (_currentRunMode == RunMode.SingleInstruction) {
                                stop(StopReason.Debug, 0);
                            }
                        }

                        somethingDone = true;
                    }
                } catch (MachineInterrupt interrupt) {
                    raiseInterrupt(interrupt);
                    somethingDone = true;
                }

                // End of the cycle - should we stop?
                if (_currentRunMode == RunMode.SingleCycle) {
                    stop(StopReason.Debug, 0);
                }

                if (!somethingDone) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                    }
                }
            }
        }

        synchronized(_storageLocks) {
            _storageLocks.remove(this);
        }

        LOGGER.info(String.format("InstructionProcessor worker %s Terminating", getName()));
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Public instance methods (only for consumption by FunctionHandlers)
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * Protected workings of the CR instruction.
     * If A(a) matches the contents of U, then A(a+1) is written to U
     * @return true if A(a) matched the contents of U, else false
     * @throws MachineInterrupt if an interrupt needs to be raised
     * @throws UnresolvedAddressException if an address is not fully resolved (basic mode indirect address only)
     */
    public boolean conditionalReplace(
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        int relAddress = calculateRelativeAddressForGRSOrStorage(0);
        int baseRegisterIndex = findBaseRegisterIndex(relAddress, false);
        BaseRegister baseRegister = _baseRegisters[baseRegisterIndex];
        baseRegister.checkAccessLimits(relAddress, false, true, true, _indicatorKeyRegister.getAccessInfo());
        AbsoluteAddress absAddress = getAbsoluteAddress(baseRegister, relAddress);
        setStorageLock(absAddress);

        long value;
        checkBreakpoint(BreakpointComparison.Read, absAddress);
        try {
            value = _inventoryManager.getStorageValue(absAddress);
        } catch (AddressLimitsException
            | UPINotAssignedException
            | UPIProcessorTypeException ex) {
            throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.ReadAccessViolation, false);
        }

        if (value == this.getExecOrUserARegister((int) _currentInstruction.getA()).getW()) {
            checkBreakpoint(BreakpointComparison.Write, absAddress);
            long newValue = this.getExecOrUserARegister((int) _currentInstruction.getA() + 1).getW();
            try {
                _inventoryManager.setStorageValue(absAddress, newValue);
                return true;
            } catch (AddressLimitsException
                | UPINotAssignedException
                | UPIProcessorTypeException ex) {
                throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.WriteAccessViolation, false);
            }
        }

        return false;
    }

    /**
     * Retrieves a BankDescriptor to describe the given named bank.
     * The bank name is in L,BDI format.
     * @param bankLevel level of the bank, 0 to 7
     * @param bankDescriptorIndex BDI of the bank
     * @return BankDescriptor object
     */
    public BankDescriptor findBankDescriptor(
        final int bankLevel,
        final int bankDescriptorIndex
    ) throws MachineInterrupt {
        // The bank descriptor tables for bank levels 0 through 7 are described by the banks based on B16 through B23.
        // The bank descriptor will be the {n}th bank descriptor in the particular bank descriptor table,
        // where {n} is the bank descriptor index.
        assert((bankLevel >= 0) && (bankLevel <= 7));
        assert((bankDescriptorIndex >= 0) && (bankDescriptorIndex <= 077777));

        int bdRegIndex = bankLevel + 16;
        if (_baseRegisters[bdRegIndex]._voidFlag) {
            throw new AddressingExceptionInterrupt(AddressingExceptionInterrupt.Reason.FatalAddressingException,
                                                   bankLevel,
                                                   bankDescriptorIndex);
        }

        //  bdStorage contains the BDT for the given bank_name level
        //  bdTableOffset indicates the offset into the BDT, where the bank descriptor is to be found.
        ArraySlice bdStorage = _baseRegisters[bdRegIndex]._storage;
        int bdTableOffset = bankDescriptorIndex * 8;    // 8 being the size of a BD in words
        if (bdTableOffset + 8 > bdStorage.getSize()) {
            throw new AddressingExceptionInterrupt(AddressingExceptionInterrupt.Reason.FatalAddressingException,
                                                   bankLevel,
                                                   bankDescriptorIndex);
        }

        //  Create and return a BankDescriptor object
        return new BankDescriptor(bdStorage, bdTableOffset);
    }

    /**
     * Calculates the raw relative address (the U) for the current instruction presuming basic mode (even if it isn't set),
     * honors any indirect addressing, and returns the index of the basic mode bank (12-15) which corresponds to the
     * final address, increment the X registers if/as appropriate, but not updating the designator register.
     * Mainly for TRA instruction...
     * @return relative address for the current instruction
     */
    public int getBasicModeBankRegisterIndex(
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        IndexRegister xReg = null;
        int xx = (int) _currentInstruction.getX();
        if (xx != 0) {
            xReg = getExecOrUserXRegister(xx);
        }
        long addend1 = _currentInstruction.getU();
        long addend2 = 0;
        if (xReg != null) {
            addend2 = xReg.getSignedXM();
            xReg.incrementModifier18();
        }

        long relativeAddress = OnesComplement.add36Simple(addend1, addend2);
        if (relativeAddress == 0777777) {
            relativeAddress = 0;
        }

        int brIndex = findBasicModeBank((int) relativeAddress, false);

        //  Did we find a bank, and are we doing indirect addressing?
        if ((brIndex > 0) && (_currentInstruction.getI() != 0)) {
            //  Increment the X register (if any) indicated by F0 (if H bit is set, of course)
            incrementIndexRegisterInF0();
            BaseRegister br = _baseRegisters[brIndex];

            //  Ensure we can read from the selected bank
            if (!isReadAllowed(br)) {
                throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.ReadAccessViolation, false);
            }
            br.checkAccessLimits((int) relativeAddress, false, true, false, _indicatorKeyRegister.getAccessInfo());

            //  Get xhiu fields from the referenced word, and place them into _currentInstruction,
            //  then throw UnresolvedAddressException so the caller knows we're not done here.
            int wx = (int) relativeAddress - br._lowerLimitNormalized;
            _currentInstruction.setXHIU(br._storage.get(wx));
            throw new UnresolvedAddressException();
        }

        //  We're at our final destination
        return brIndex;
    }

    /**
     * Retrieves consecutive word values for double or multiple-word transfer operations (e.g., DL, LRS, etc).
     * The assumption is that this call is made for a single iteration of an instruction.  Per doc 9.2, effective
     * relative address (U) will be calculated only once; however, access checks must succeed for all accesses.
     * We presume we are retrieving from GRS or from storage - i.e., NOT allowing immediate addressing.
     * Also, we presume that we are doing full-word transfers - no partial word.
     * @param grsCheck true if we should check U to see if it is a GRS location
     * @param operands Where we store the resulting operands - the length of this array defines how many operands we retrieve
     * @throws MachineInterrupt if an interrupt needs to be raised
     * @throws UnresolvedAddressException if an address is not fully resolved (basic mode indirect address only)
     */
    public void getConsecutiveOperands(
        final boolean grsCheck,
        long[] operands
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        //  Get the relative address so we can do a grsCheck
        int relAddress = calculateRelativeAddressForGRSOrStorage(0);
        incrementIndexRegisterInF0();

        //  If this is a GRS reference - we do not need to look for containing banks or validate storage limits.
        if ((grsCheck)
                && ((_designatorRegister.getBasicModeEnabled()) || (_currentInstruction.getB() == 0))
                && (relAddress < 0200)) {
            //  For multiple accesses, advancing beyond GRS 0177 wraps back to zero.
            //  Do accessibility checks for each GRS access
            int grsIndex = relAddress;
            for (int ox = 0; ox < operands.length; ++ox, ++grsIndex) {
                if (grsIndex == 0200) {
                    grsIndex = 0;
                }

                if (!GeneralRegisterSet.isAccessAllowed(grsIndex, _designatorRegister.getProcessorPrivilege(), false)) {
                    throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.ReadAccessViolation, false);
                }

                operands[ox] = _generalRegisterSet.getRegister(grsIndex).getW();
            }

            return;
        }

        //  Get base register and check storage and access limits
        int brIndex = findBaseRegisterIndex(relAddress, false);
        BaseRegister bReg = _baseRegisters[brIndex];
        bReg.checkAccessLimits(relAddress, operands.length, true, false, _indicatorKeyRegister.getAccessInfo());

        //  Lock the storage
        AbsoluteAddress[] absAddresses = new AbsoluteAddress[operands.length];
        for (int ax = 0; ax < operands.length; ++ax ) {
            absAddresses[ax] = getAbsoluteAddress(bReg, relAddress + ax);
        }
        setStorageLocks(absAddresses);

        //  Retrieve the operands
        int offset = relAddress - bReg._lowerLimitNormalized;
        for (int ox = 0; ox < operands.length; ++ox) {
            checkBreakpoint(BreakpointComparison.Read, absAddresses[ox]);
            operands[ox] = bReg._storage.get(offset++);
        }
    }

    /**
     * Retrieves a reference to the GeneralRegister indicated by the register index...
     * i.e., registerIndex == 0 returns either A0 or EA0, depending on the designator register.
     * @param registerIndex A register index of interest
     * @return GRS register
     */
    public GeneralRegister getExecOrUserARegister(
        final int registerIndex
    ) {
        return _generalRegisterSet.getRegister(getExecOrUserARegisterIndex(registerIndex));
    }

    /**
     * Retrieves the GRS index of the exec or user register indicated by the register index...
     * i.e., registerIndex == 0 returns the GRS index for either A0 or EA0, depending on the designator register.
     * @param registerIndex A register index of interest
     * @return GRS register index
     */
    public int getExecOrUserARegisterIndex(
        final int registerIndex
    ) {
        return registerIndex + (_designatorRegister.getExecRegisterSetSelected() ? GeneralRegisterSet.EA0 : GeneralRegisterSet.A0);
    }

    /**
     * Retrieves a reference to the GeneralRegister indicated by the register index...
     * i.e., registerIndex == 0 returns either R0 or ER0, depending on the designator register.
     * @param registerIndex R register index of interest
     * @return GRS register
     */
    public GeneralRegister getExecOrUserRRegister(
        final int registerIndex
    ) {
        return _generalRegisterSet.getRegister(getExecOrUserRRegisterIndex(registerIndex));
    }

    /**
     * Retrieves a reference to the IndexRegister indicated by the register index...
     * i.e., registerIndex == 0 returns either X0 or EX0, depending on the designator register.
     * @param registerIndex X register index of interest
     * @return GRS register
     */
    public IndexRegister getExecOrUserXRegister(
        final int registerIndex
    ) {
        return (IndexRegister)_generalRegisterSet.getRegister(getExecOrUserXRegisterIndex(registerIndex));
    }

    /**
     * It has been determined that the u (and possibly h and i) fields comprise requested data.
     * Load the value indicated in F0 (_currentInstruction) as follows:
     *      For Processor Privilege 0,1
     *          value is 24 bits for DR.11 (exec 24bit indexing enabled) true, else 18 bits
     *      For Processor Privilege 2,3
     *          value is 24 bits for FO.i set, else 18 bits
     * If F0.x is zero, the immediate value is taken from the h,i, and u fields (unsigned), and negative zero is eliminated.
     * For F0.x nonzero, the immediate value is the sum of the u field (unsigned) with the F0.x(mod) signed field.
     *      For Extended Mode, with Processor Privilege 0,1 and DR.11 set, index modifiers are 24 bits; otherwise, they are 18 bits.
     *      For Basic Mode, index modifiers are always 18 bits.
     * In either case, the value will be left alone for j-field=016, and sign-extended for j-field=017.
     * @return immediate operand value
     */
    public long getImmediateOperand(
    ) {
        boolean exec24Index = _designatorRegister.getExecutive24BitIndexingEnabled();
        int privilege = _designatorRegister.getProcessorPrivilege();
        boolean valueIs24Bits = ((privilege < 2) && exec24Index) || ((privilege > 1) && (_currentInstruction.getI() != 0));
        long value;

        if (_currentInstruction.getX() == 0) {
            //  No indexing (x-field is zero).  Value is derived from h, i, and u fields.
            //  Get the value from h,i,u, and eliminate negative zero.
            value = _currentInstruction.getHIU();
            if (value == 0777777) {
                value = 0;
            }

            if ((_currentInstruction.getJ() == 017) && ((value & 0400000) != 0)) {
                value |= 0_777777_000000L;
            }

        } else {
            //  Value is taken only from the u field, and we eliminate negative zero at this point.
            value = _currentInstruction.getU();
            if ( value == 0177777 )
                value = 0;

            //  Add the contents of Xx(m), and do index register incrementation if appropriate.
            IndexRegister xReg = getExecOrUserXRegister((int)_currentInstruction.getX());

            //  24-bit indexing?
           if (!_designatorRegister.getBasicModeEnabled() && (privilege < 2) && exec24Index) {
                //  Add the 24-bit modifier
                value = OnesComplement.add36Simple(value, xReg.getXM24());
                if (_currentInstruction.getH() != 0) {
                    xReg.incrementModifier24();
                }
            } else {
                //  Add the 18-bit modifier
                value = OnesComplement.add36Simple(value, xReg.getXM());
                if (_currentInstruction.getH() != 0) {
                    xReg.incrementModifier18();
                }
            }
        }

        //  Truncate the result to the proper size, then sign-extend if appropriate to do so.
        boolean extend = _currentInstruction.getJ() == 017;
        if (valueIs24Bits) {
            value &= 077_777777L;
            if (extend && (value & 040_000000L) != 0) {
                value |= 0_777700_000000L;
            }
        } else {
            value &= 0_777777L;
            if (extend && (value & 0_400000) != 0) {
                value |= 0_777777_000000L;
            }
        }

        return value;
    }

    /**
     * See getImmediateOperand() above.
     * This is similar, however the calculated U field is only ever 16 or 18 bits, and is never sign-extended.
     * Also, we do not rely upon j-field for anything, as that has no meaning for conditionalJump instructions.
     * @param updateDesignatorRegister if true and if we are in basic mode, we update the basic mode bank selection bit
     *                                 in the designator register if necessary
     * @return conditionalJump operand value
     * @throws MachineInterrupt if an interrupt needs to be raised
     * @throws UnresolvedAddressException if an address is not fully resolved (basic mode indirect address only)
     */
    public int getJumpOperand(
        final boolean updateDesignatorRegister
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        int relAddress = calculateRelativeAddressForJump(0);

        //  The following bit is how we deal with indirect addressing for basic mode.
        //  If we are doing that, it will update the U portion of the current instruction with new address information,
        //  then throw UnresolvedAddressException which will eventually route us back through here again, but this
        //  time with new address info (in reladdress), and we keep doing this until we're not doing indirect addressing.
        if (_designatorRegister.getBasicModeEnabled() && (_currentInstruction.getI() != 0)) {
            findBaseRegisterIndex(relAddress, updateDesignatorRegister);
        } else {
            incrementIndexRegisterInF0();
        }

        return relAddress;
    }

    /**
     * The general case of retrieving an operand, including all forms of addressing and partial word access.
     * Instructions which use the j-field as part of the function code will likely set allowImmediate and
     * allowPartial false.
     * @param grsDestination true if we are going to put this value into a GRS location
     * @param grsCheck true if we should consider GRS for addresses < 0200 for our source
     * @param allowImmediate true if we should allow immediate addressing
     * @param allowPartial true if we should do partial word transfers (presuming we are not in a GRS address)
     * @return operand value
     * @throws MachineInterrupt if any interrupt needs to be raised.
     *                          In this case, the instruction is incomplete and should be retried if appropriate.
     * @throws UnresolvedAddressException if address resolution is unfinished (such as can happen in Basic Mode with
     *                                    Indirect Addressing).  In this case, caller should call back here again after
     *                                    checking for any pending interrupts.
     */
    public long getOperand(
        final boolean grsDestination,
        final boolean grsCheck,
        final boolean allowImmediate,
        final boolean allowPartial
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        int jField = (int)_currentInstruction.getJ();
        if (allowImmediate) {
            //  j-field is U or XU? If so, get the value from the instruction itself (immediate addressing)
            if (jField >= 016) {
                return getImmediateOperand();
            }
        }

        int relAddress = calculateRelativeAddressForGRSOrStorage(0);

        //  Loading from GRS?  If so, go get the value.
        //  If grsDestination is true, get the full value. Otherwise, honor j-field for partial-word transfer.
        //  See hardware guide section 4.3.2 - any GRS-to-GRS transfer is full-word, regardless of j-field.
        if ((grsCheck)
                && ((_designatorRegister.getBasicModeEnabled()) || (_currentInstruction.getB() == 0))
                && (relAddress < 0200)) {
            incrementIndexRegisterInF0();

            //  First, do accessibility checks
            if (!GeneralRegisterSet.isAccessAllowed(relAddress, _designatorRegister.getProcessorPrivilege(), false)) {
                throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.ReadAccessViolation, true);
            }

            //  If we are GRS or not allowing partial word transfers, do a full word.
            //  Otherwise, honor partial word transfering.
            if (grsDestination || !allowPartial) {
                return _generalRegisterSet.getRegister(relAddress).getW();
            } else {
                boolean qWordMode = _designatorRegister.getQuarterWordModeEnabled();
                return extractPartialWord(_generalRegisterSet.getRegister(relAddress).getW(), jField, qWordMode);
            }
        }

        //  Loading from storage.  Do so, then (maybe) honor partial word handling.
        int baseRegisterIndex = findBaseRegisterIndex(relAddress, false);
        BaseRegister baseRegister = _baseRegisters[baseRegisterIndex];
        baseRegister.checkAccessLimits(relAddress, false, true, false, _indicatorKeyRegister.getAccessInfo());

        incrementIndexRegisterInF0();

        AbsoluteAddress absAddress = getAbsoluteAddress(baseRegister, relAddress);
        setStorageLock(absAddress);
        checkBreakpoint(BreakpointComparison.Read, absAddress);

        int readOffset = relAddress - baseRegister._lowerLimitNormalized;
        long value = baseRegister._storage.get(readOffset);
        if (allowPartial) {
            boolean qWordMode = _designatorRegister.getQuarterWordModeEnabled();
            value = extractPartialWord(value, jField, qWordMode);
        }

        return value;
    }

    /**
     * Retrieves a partial-word operand from storage, depending upon the values of jField and quarterWordMode.
     * This is never a GRS reference, nor immediate (nor a conditionalJump or shift, for that matter).
     * @param jField not necessarily from j-field, this indicates the partial word to be stored
     * @param quarterWordMode needs to be set true for storing quarter words
     * @return operand value
     * @throws MachineInterrupt if any interrupt needs to be raised.
     *                          In this case, the instruction is incomplete and should be retried if appropriate.
     * @throws UnresolvedAddressException if address resolution is unfinished (such as can happen in Basic Mode with
     *                                    Indirect Addressing).  In this case, caller should call back here again after
     *                                    checking for any pending interrupts.
     */
    public long getPartialOperand(
        final int jField,
        final boolean quarterWordMode
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        int relAddress = calculateRelativeAddressForGRSOrStorage(0);
        int baseRegisterIndex = findBaseRegisterIndex(relAddress, false);
        incrementIndexRegisterInF0();

        BaseRegister baseRegister = _baseRegisters[baseRegisterIndex];
        baseRegister.checkAccessLimits(relAddress, false, true, false, _indicatorKeyRegister.getAccessInfo());

        AbsoluteAddress absAddress = getAbsoluteAddress(baseRegister, relAddress);
        setStorageLock(absAddress);
        checkBreakpoint(BreakpointComparison.Read, absAddress);

        int readOffset = relAddress - baseRegister._lowerLimitNormalized;
        long value = baseRegister._storage.get(readOffset);
        return extractPartialWord(value, jField, quarterWordMode);
    }

    /**
     * Increments the register indicated by the current instruction (F0) appropriately.
     * Only effective if f.x is non-zero.
     */
    public void incrementIndexRegisterInF0(
    ) {
        if ((_currentInstruction.getX() != 0) && (_currentInstruction.getH() != 0)) {
            IndexRegister iReg = getExecOrUserXRegister((int)_currentInstruction.getX());
            if (!_designatorRegister.getBasicModeEnabled()
                    && (_designatorRegister.getExecutive24BitIndexingEnabled())
                    && (_designatorRegister.getProcessorPrivilege() < 2)) {
                iReg.incrementModifier24();
            } else {
                iReg.incrementModifier18();
            }
        }
    }

    /**
     * The general case of incrementing an operand by some value, including all forms of addressing and partial word access.
     * Instructions which use the j-field as part of the function code will likely set allowPartial false.
     * Sets carry and overflow designators if appropriate.
     * @param grsCheck true if we should consider GRS for addresses < 0200 for our source
     * @param allowPartial true if we should do partial word transfers (presuming we are not in a GRS address)
     * @param incrementValue how much we increment storage by - positive or negative, but always ones-complement
     * @param twosComplement true to use twos-complement arithmetic - otherwise use ones-complement
     * @return true if either the starting or ending value of the operand is +/- zero
     * @throws MachineInterrupt if any interrupt needs to be raised.
     *                          In this case, the instruction is incomplete and should be retried if appropriate.
     * @throws UnresolvedAddressException if address resolution is unfinished (such as can happen in Basic Mode with
     *                                    Indirect Addressing).  In this case, caller should call back here again after
     *                                    checking for any pending interrupts.
     */
    public boolean incrementOperand(
        final boolean grsCheck,
        final boolean allowPartial,
        final long incrementValue,
        final boolean twosComplement
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        int jField = (int)_currentInstruction.getJ();
        int relAddress = calculateRelativeAddressForGRSOrStorage(0);

        //  Loading from GRS?  If so, go get the value.
        //  If grsDestination is true, get the full value. Otherwise, honor j-field for partial-word transfer.
        //  See hardware guide section 4.3.2 - any GRS-to-GRS transfer is full-word, regardless of j-field.
        boolean result = false;
        if ((grsCheck)
                && ((_designatorRegister.getBasicModeEnabled()) || (_currentInstruction.getB() == 0))
                && (relAddress < 0200)) {
            incrementIndexRegisterInF0();

            //  This is a GRS address.  Do accessibility checks
            if (!GeneralRegisterSet.isAccessAllowed(relAddress, _designatorRegister.getProcessorPrivilege(), false)) {
                throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.ReadAccessViolation, true);
            }

            //  Ignore partial-word transfers.
            GeneralRegister reg = _generalRegisterSet.getRegister(relAddress);
            if (twosComplement) {
                long sum = reg.getW();
                if (sum == 0) {
                    result = true;
                }
                sum += OnesComplement.getNative36(incrementValue);
                if (sum == 0) {
                    result = true;
                }

                reg.setW(sum);
                _designatorRegister.setCarry(false);
                _designatorRegister.setOverflow(false);
            } else {
                long sum = reg.getW();
                result = OnesComplement.isZero36(sum);
                OnesComplement.Add36Result ocResult = new OnesComplement.Add36Result();
                OnesComplement.add36(sum, incrementValue, ocResult);
                if (OnesComplement.isZero36(ocResult._sum)) {
                    result = true;
                }

                reg.setW(ocResult._sum);
                _designatorRegister.setCarry(ocResult._carry);
                _designatorRegister.setOverflow(ocResult._overflow);
            }

            return result;
        }

        //  Storage operand.  Maybe do partial-word addressing
        int baseRegisterIndex = findBaseRegisterIndex(relAddress, false);
        incrementIndexRegisterInF0();

        BaseRegister baseRegister = _baseRegisters[baseRegisterIndex];
        baseRegister.checkAccessLimits(relAddress, false, true, true, _indicatorKeyRegister.getAccessInfo());

        AbsoluteAddress absAddress = getAbsoluteAddress(baseRegister, relAddress);
        setStorageLock(absAddress);
        checkBreakpoint(BreakpointComparison.Read, absAddress);

        int readOffset = relAddress - baseRegister._lowerLimitNormalized;
        long storageValue = baseRegister._storage.get(readOffset);
        boolean qWordMode = _designatorRegister.getQuarterWordModeEnabled();
        long sum = allowPartial ? extractPartialWord(storageValue, jField, qWordMode) : storageValue;

        if (twosComplement) {
            if (sum == 0) {
                result = true;
            }
            sum += OnesComplement.getNative36(incrementValue);
            if (sum == 0) {
                result = true;
            }

            _designatorRegister.setCarry(false);
            _designatorRegister.setOverflow(false);
        } else {
            if (OnesComplement.isZero36(sum)) {
                result = true;
            }
            OnesComplement.Add36Result ocResult = new OnesComplement.Add36Result();
            OnesComplement.add36(sum, incrementValue, ocResult);
            if (OnesComplement.isZero36(ocResult._sum)) {
                result = true;
            }

            _designatorRegister.setCarry(ocResult._carry);
            _designatorRegister.setOverflow(ocResult._overflow);
            sum = ocResult._sum;
        }

        long storageResult = allowPartial ? injectPartialWord(storageValue, sum, jField, qWordMode) : sum;
        baseRegister._storage.set(readOffset, storageResult);
        return result;
    }

    /**
     * Updates PAR.PC and sets the prevent-increment flag according to the given parameters.
     * Used for simple conditionalJump instructions.
     * @param counter program counter value
     * @param preventIncrement true to set the prevent-increment flag
     */
    public void setProgramCounter(
        final int counter,
        final boolean preventIncrement
    ) {
        this._programAddressRegister.setProgramCounter(counter);
        this._preventProgramCounterIncrement = preventIncrement;
    }

    /**
     * Stores consecutive word values for double or multiple-word transfer operations (e.g., DS, SRS, etc).
     * The assumption is that this call is made for a single iteration of an instruction.  Per doc 9.2, effective
     * relative address (U) will be calculated only once; however, access checks must succeed for all accesses.
     * We presume that we are doing full-word transfers - no partial word.
     * @param grsCheck true if we should check U to see if it is a GRS location
     * @param operands The operands to be stored
     * @throws MachineInterrupt if any interrupt needs to be raised.
     *                          In this case, the instruction is incomplete and should be retried if appropriate.
     * @throws UnresolvedAddressException if address resolution is unfinished (such as can happen in Basic Mode with
     *                                    Indirect Addressing).  In this case, caller should call back here again after
     *                                    checking for any pending interrupts.
     */
    public void storeConsecutiveOperands(
        final boolean grsCheck,
        long[] operands
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        //  Get the first relative address so we can do a grsCheck
        int relAddress = calculateRelativeAddressForGRSOrStorage(0);

        if ((grsCheck)
                && ((_designatorRegister.getBasicModeEnabled()) || (_currentInstruction.getB() == 0))
                && (relAddress < 0200)) {
            //  For multiple accesses, advancing beyond GRS 0177 wraps back to zero.
            //  Do accessibility checks for each GRS access
            int grsIndex = relAddress;
            for (int ox = 0; ox < operands.length; ++ox, ++grsIndex) {
                if (grsIndex == 0200) {
                    grsIndex = 0;
                }

                if (!GeneralRegisterSet.isAccessAllowed(grsIndex, _designatorRegister.getProcessorPrivilege(), false)) {
                    throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.ReadAccessViolation, false);
                }

                _generalRegisterSet.setRegister(grsIndex, operands[ox]);
            }

            incrementIndexRegisterInF0();
        } else {
            //  Get base register and check storage and access limits
            int brIndex = findBaseRegisterIndex(relAddress, false);
            BaseRegister bReg = _baseRegisters[brIndex];
            bReg.checkAccessLimits(relAddress, operands.length, false, true, _indicatorKeyRegister.getAccessInfo());

            //  Lock the storage
            AbsoluteAddress[] absAddresses = new AbsoluteAddress[operands.length];
            for (int ax = 0; ax < operands.length; ++ax ) {
                absAddresses[ax] = getAbsoluteAddress(bReg, relAddress + ax);
            }
            setStorageLocks(absAddresses);

            //  Store the operands
            int offset = relAddress - bReg._lowerLimitNormalized;
            for (int ox = 0; ox < operands.length; ++ox) {
                checkBreakpoint(BreakpointComparison.Write, absAddresses[ox]);
                bReg._storage.set(offset++, operands[ox]);
            }

            incrementIndexRegisterInF0();
        }
    }

    /**
     * General case of storing an operand either to storage or to a GRS location
     * @param grsSource true if the value came from a register, so we know whether we need to ignore partial-word transfers
     * @param grsCheck true if relative addresses < 0200 should be considered GRS locations
     * @param checkImmediate true if we should consider j-fields 016 and 017 as immediate addressing (and throw away the operand)
     * @param allowPartial true if we should allow partial-word transfers (subject to GRS-GRS transfers)
     * @param operand value to be stored (36 bits significant)
     * @throws MachineInterrupt if any interrupt needs to be raised.
     *                          In this case, the instruction is incomplete and should be retried if appropriate.
     * @throws UnresolvedAddressException if address resolution is unfinished (such as can happen in Basic Mode with
     *                                    Indirect Addressing).  In this case, caller should call back here again after
     *                                    checking for any pending interrupts.
     */
    public void storeOperand(
        final boolean grsSource,
        final boolean grsCheck,
        final boolean checkImmediate,
        final boolean allowPartial,
        final long operand
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        //  If we allow immediate addressing mode and j-field is U or XU... we do nothing.
        int jField = (int)_currentInstruction.getJ();
        if ((checkImmediate) && (jField >= 016)) {
            return;
        }

        int relAddress = calculateRelativeAddressForGRSOrStorage(0);

        if ((grsCheck)
                && ((_designatorRegister.getBasicModeEnabled()) || (_currentInstruction.getB() == 0))
                && (relAddress < 0200)) {
            incrementIndexRegisterInF0();

            //  First, do accessibility checks
            if (!GeneralRegisterSet.isAccessAllowed(relAddress, _designatorRegister.getProcessorPrivilege(), true)) {
                throw new ReferenceViolationInterrupt(ReferenceViolationInterrupt.ErrorType.WriteAccessViolation, true);
            }

            //  If we are GRS or not allowing partial word transfers, do a full word.
            //  Otherwise, honor partial word transfer.
            if (!grsSource && allowPartial) {
                boolean qWordMode = _designatorRegister.getQuarterWordModeEnabled();
                long originalValue = _generalRegisterSet.getRegister(relAddress).getW();
                long newValue = injectPartialWord(originalValue, operand, jField, qWordMode);
                _generalRegisterSet.setRegister(relAddress, newValue);
            } else {
                _generalRegisterSet.setRegister(relAddress, operand);
            }

            return;
        }

        //  This is going to be a storage thing...
        int baseRegisterIndex = findBaseRegisterIndex(relAddress, false);
        BaseRegister bReg = _baseRegisters[baseRegisterIndex];
        bReg.checkAccessLimits(relAddress, false, false, true, _indicatorKeyRegister.getAccessInfo());

        incrementIndexRegisterInF0();

        AbsoluteAddress absAddress = getAbsoluteAddress(bReg, relAddress);
        setStorageLock(absAddress);
        checkBreakpoint(BreakpointComparison.Write, absAddress);

        int offset = relAddress - bReg._lowerLimitNormalized;
        if (allowPartial) {
            boolean qWordMode = _designatorRegister.getQuarterWordModeEnabled();
            long originalValue = bReg._storage.get(offset);
            long newValue = injectPartialWord(originalValue, operand, jField, qWordMode);
            bReg._storage.set(offset, newValue);
        } else {
            bReg._storage.set(offset, operand);
        }
    }

    /**
     * Stores the right-most bits of an operand to a partial word in storage.
     * @param operand value to be stored (up to 36 bits significant)
     * @param jField not necessarily from j-field, this indicates the partial word to be stored
     * @param quarterWordMode needs to be set true for storing quarter words
     * @throws MachineInterrupt if any interrupt needs to be raised.
     *                          In this case, the instruction is incomplete and should be retried if appropriate.
     * @throws UnresolvedAddressException if address resolution is unfinished (such as can happen in Basic Mode with
     *                                    Indirect Addressing).  In this case, caller should call back here again after
     *                                    checking for any pending interrupts.
     */
    public void storePartialOperand(
        final long operand,
        final int jField,
        final boolean quarterWordMode
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        int relAddress = calculateRelativeAddressForGRSOrStorage(0);
        int baseRegisterIndex = findBaseRegisterIndex(relAddress, false);
        BaseRegister bReg = _baseRegisters[baseRegisterIndex];
        bReg.checkAccessLimits(relAddress, false, false, true, _indicatorKeyRegister.getAccessInfo());

        incrementIndexRegisterInF0();

        AbsoluteAddress absAddress = getAbsoluteAddress(bReg, relAddress);
        setStorageLock(absAddress);
        checkBreakpoint(BreakpointComparison.Write, absAddress);

        int offset = relAddress - bReg._lowerLimitNormalized;
        long originalValue = bReg._storage.get(offset);
        long newValue = injectPartialWord(originalValue, operand, jField, quarterWordMode);
        bReg._storage.set(offset, newValue);
    }

    /**
     * Updates S1 of a lock word under storage lock.
     * Does *NOT* increment the x-register in F0 (if specified), even if the h-bit is set.
     * @param flag if true, we expect the lock to be clear, and we set it.
     *              if false, we expect the lock to be set, and we clear it.
     * @throws MachineInterrupt if any interrupt needs to be raised.
     *                          In this case, the instruction is incomplete and should be retried if appropriate.
     * @throws UnresolvedAddressException if address resolution is unfinished (such as can happen in Basic Mode with
     *                                    Indirect Addressing).  In this case, caller should call back here again after
     *                                    checking for any pending interrupts.
     */
    public void testAndStore(
        final boolean flag
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        int relAddress = calculateRelativeAddressForGRSOrStorage(0);
        int baseRegisterIndex = findBaseRegisterIndex(relAddress, false);
        BaseRegister bReg = _baseRegisters[baseRegisterIndex];
        bReg.checkAccessLimits(relAddress, false, true, true, _indicatorKeyRegister.getAccessInfo());

        AbsoluteAddress absAddress = getAbsoluteAddress(bReg, relAddress);
        setStorageLock(absAddress);
        checkBreakpoint(BreakpointComparison.Read, absAddress);

        int offset = relAddress - bReg._lowerLimitNormalized;
        long value = bReg._storage.get(offset);
        if (flag) {
            //  we want to set the lock, so it needs to be clear
            if ((value & 0_010000_000000) != 0) {
                throw new TestAndSetInterrupt(baseRegisterIndex, relAddress);
            }

            value = injectPartialWord(value, 01, InstructionWord.S1, false);
        } else {
            //  We want to clear the lock, so it needs to be set
            if ((value & 0_010000_000000) == 0) {
                throw new TestAndSetInterrupt(baseRegisterIndex, relAddress);
            }

            value = injectPartialWord(value, 0, InstructionWord.S1, false);
        }

        checkBreakpoint(BreakpointComparison.Write, absAddress);
        bReg._storage.set(offset, value);
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Public instance methods
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * IPs have no ancestors
     * @param ancestor proposed ancestor node
     * @return false always
     */
    @Override
    public boolean canConnect(
        final Node ancestor
    ) {
        return false;
    }

    /**
     * For debugging
     * @param writer where we write the dump
     */
    @Override
    public void dump(
        final BufferedWriter writer
    ) {
        super.dump(writer);
        try {
            writer.write("");//TODO actually, a whole lot to do here
        } catch (IOException ex) {
            LOGGER.catching(ex);
        }
    }

    /**
     * Worker interface implementation
     * @return our node name
     */
    @Override
    public String getWorkerName(
    ) {
        return getName();
    }

    /**
     * Starts the instantiated thread
     */
    @Override
    public final void initialize(
    ) {
        _workerThread.start();
        while (!_workerThread.isAlive()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
            }
        }
    }

    /**
     * Invoked when any other node decides to signal us
     * @param source node from which the signal came
     */
    @Override
    public void signal(
        final Node source
    ) {
        //TODO IPL interrupts
    }

    /**
     * Causes the IP to skip the next instruction.  Implemented by simply incrementing the PC.
     */
    public void skipNextInstruction(
    ) {
        _programAddressRegister.setProgramCounter(_programAddressRegister.getProgramCounter() + 1);
    }

    /**
     * Starts the processor.
     * Since the worker thread is always running, this merely wakes it up so that it can resume instruction processing.
     */
    public void start(
    ) {
        synchronized(this) {
            _runningFlag = true;
            this.notify();
        }
    }

    /**
     * Stops the processor.
     * More accurately, it puts the worker thread into not-running state, such that it no longer processes instructions.
     * Rather, it will simply sleep until such time as it is placed back into running state.
     * @param stopReason enumeration indicating the reason for the stop
     * @param detail 36-bit word further describing the stop reason
     */
    public void stop(
        final StopReason stopReason,
        final long detail
    ) {
        synchronized(this) {
            if (_runningFlag) {
                _latestStopReason = stopReason;
                _latestStopDetail = detail;
                _runningFlag = false;
                System.out.println(String.format("%s Stopping:%s Detail:%o",
                                                 getName(),
                                                 stopReason.toString(),
                                                 _latestStopDetail));//TODO remove later
                LOGGER.error(String.format("%s Stopping:%s Detail:%o",
                                           getName(),
                                           stopReason.toString(),
                                           _latestStopDetail));
                this.notify();
            }
        }
    }

    /**
     * Called during config tear-down - terminate the active thread
     */
    @Override
    public void terminate(
    ) {
        _workerTerminate = true;
        synchronized(_workerThread) {
            _workerThread.notify();
        }

        while (_workerThread.isAlive()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
            }
        }
    }

    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Static methods
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * Takes a 36-bit value as input, and returns a partial-word value depending upon
     * the partialWordIndicator (presumably taken from the j-field of an instruction)
     * and the quarterWordMode flag (presumably taken from the designator register).
     * @param source 36-bit source word
     * @param partialWordIndicator indicator of the desired partial word
     * @param quarterWordMode true if we're in quarter word mode, else false
     * @return partial word
     */
    private static long extractPartialWord(
        final long source,
        final int partialWordIndicator,
        final boolean quarterWordMode
    ) {
        switch (partialWordIndicator) {
            case InstructionWord.W:     return source & OnesComplement.BIT_MASK_36;
            case InstructionWord.H2:    return Word36.getH2(source);
            case InstructionWord.H1:    return Word36.getH1(source);
            case InstructionWord.XH2:   return Word36.getXH2(source);
            case InstructionWord.XH1:   // XH1 or Q2
                if (quarterWordMode) {
                    return Word36.getQ2(source);
                } else {
                    return Word36.getXH1(source);
                }
            case InstructionWord.T3:    // T3 or Q4
                if (quarterWordMode) {
                    return Word36.getQ4(source);
                } else {
                    return Word36.getXT3(source);
                }
            case InstructionWord.T2:    // T2 or Q3
                if (quarterWordMode) {
                    return Word36.getQ3(source);
                } else {
                    return Word36.getXT2(source);
                }
            case InstructionWord.T1:    // T1 or Q1
                if (quarterWordMode) {
                    return Word36.getQ1(source);
                } else {
                    return Word36.getXT1(source);
                }
            case InstructionWord.S6:    return Word36.getS6(source);
            case InstructionWord.S5:    return Word36.getS5(source);
            case InstructionWord.S4:    return Word36.getS4(source);
            case InstructionWord.S3:    return Word36.getS3(source);
            case InstructionWord.S2:    return Word36.getS2(source);
            case InstructionWord.S1:    return Word36.getS1(source);
        }

        return source;
    }

    /**
     * Converts a relative address to an absolute address.
     * @param baseRegister base register associated with the relative address
     * @param relativeAddress address to be converted
     * @return absolute address object
     */
    private static AbsoluteAddress getAbsoluteAddress(
        final BaseRegister baseRegister,
        final int relativeAddress
    ) {
        short upi = baseRegister._baseAddress._upi;
        int actualOffset = relativeAddress - baseRegister._lowerLimitNormalized;
        int offset = baseRegister._baseAddress._offset + actualOffset;
        return new AbsoluteAddress(upi, baseRegister._baseAddress._segment, offset);
    }

    /**
     * Takes 36-bit values as original and new values, and injects the new value as a partial word of the original value
     * depending upon the partialWordIndicator (presumably taken from the j-field of an instruction).
     * @param originalValue original value 36-bits significant
     * @param newValue new value right-aligned in a 6, 9, 12, 18, or 36-bit significant field
     * @param partialWordIndicator corresponds to the j-field of an instruction word
     * @param quarterWordMode true to do quarter-word mode transfers, false for third-word mode
     * @return composite value with right-most significant bits of newValue replacing a partial word portion of the
     *          original value
     */
    private static long injectPartialWord(
        final long originalValue,
        final long newValue,
        final int partialWordIndicator,
        final boolean quarterWordMode
    ) {
        switch (partialWordIndicator) {
            case InstructionWord.W:     return newValue;
            case InstructionWord.H2:    return Word36.setH2(originalValue, newValue);
            case InstructionWord.H1:    return Word36.setH1(originalValue, newValue);
            case InstructionWord.XH2:   return Word36.setH2(originalValue, newValue);
            case InstructionWord.XH1:   // XH1 or Q2
                if (quarterWordMode) {
                    return Word36.setQ2(originalValue, newValue);
                } else {
                    return Word36.setH1(originalValue, newValue);
                }
            case InstructionWord.T3:    // T3 or Q4
                if (quarterWordMode) {
                    return Word36.setQ4(originalValue, newValue);
                } else {
                    return Word36.setT3(originalValue, newValue);
                }
            case InstructionWord.T2:    // T2 or Q3
                if (quarterWordMode) {
                    return Word36.setQ3(originalValue, newValue);
                } else {
                    return Word36.setT2(originalValue, newValue);
                }
            case InstructionWord.T1:    // T1 or Q1
                if (quarterWordMode) {
                    return Word36.setQ1(originalValue, newValue);
                } else {
                    return Word36.setT1(originalValue, newValue);
                }
            case InstructionWord.S6:    return Word36.setS6(originalValue, newValue);
            case InstructionWord.S5:    return Word36.setS5(originalValue, newValue);
            case InstructionWord.S4:    return Word36.setS4(originalValue, newValue);
            case InstructionWord.S3:    return Word36.setS3(originalValue, newValue);
            case InstructionWord.S2:    return Word36.setS2(originalValue, newValue);
            case InstructionWord.S1:    return Word36.setS1(originalValue, newValue);
        }

        return originalValue;
    }
}
