/*
 * Copyright (c) 2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib;

import com.kadware.komodo.baselib.ArraySlice;
import com.kadware.komodo.baselib.Word36;
import com.kadware.komodo.hardwarelib.interrupts.AddressingExceptionInterrupt;
import java.io.BufferedWriter;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Class which implements the functionality necessary for an architecturally defined system control facility.
 * Our design stipulates the existence of exactly one of these in a proper configuration.
 * This object manages all console communication, and implements the system-wide dayclock.
 * It is also responsible for creating and managing the partition data bank which is used by the operating system.
 */
@SuppressWarnings("Duplicates")
public class SystemProcessor extends Processor implements SystemConsole.Client {

    private static final Logger LOGGER = LogManager.getLogger(SystemProcessor.class);

    //  Tape Boot Procedure:
    //      A starting IP is specified, along with the device upon which the boot tape is mounted,
    //      and the disk device on which the DRS pack is mounted.
    //      The tape path is selected consisting of:
    //          the tape device on which the boot tape is mounted
    //          a channel module connected to the tape device
    //          the IOP which contains the channel module
    //      A memory block of 1792 words is allocated to contain the loader bank
    //      The first block is read from the tape into the loader bank
    //      A memory block is allocated to contain the configuration data bank, and is populated
    //      A memory block is allocated to contain the initial level 0 BDT, which contains the
    //          interrupt vector for the IPL interrupt, which contains a GOTO which transfers
    //          control to the loader bank
    //      The ICS BReg and XReg are initialized to refer to the interrupt vectors in the initial level 0 BDT,
    //          BR2 is initialized to refer to the configuration data bank,
    //          and the IP is started (which causes it to generate a class 29 interrupt - the IPL interrupt).
    //
    //  Disk Boot Procedure:
    //      A starting IP is specified, along with the device upon which the relevant DRS pack is mounted.
    //      The disk path is selected consisting of:
    //          the disk device on which the DRS pack is mounted
    //          a channel module connected to the disk device
    //          the IOP which contains the channel module
    //      A memory block of 1792 words is allocated to contain the loader bank
    //      The first one or two blocks (depending on block size) are read from the DRS pack into the loader bank
    //      A memory block is allocated to contain the configuration data bank, and is populated
    //      A memory block is allocated to contain the initial level 0 BDT, which contains the
    //          interrupt vector for the IPL interrupt, which contains a GOTO which transfers
    //          control to the loader bank
    //      The ICS BReg and XReg are initialized to refer to the interrupt vectors in the initial level 0 BDT,
    //          BR2 is initialized to refer to the configuration data bank,
    //          and the IP is started (which causes it to generate a class 29 interrupt - the IPL interrupt).
    //
    //  Notes:
    //      Loader code must know how many OS banks exist, and how big they are
    //      The loader is responsible for creating the Level 0 BDT (at a minimum)
    //      At some point, the loader must send UPI interrupts to the other IPs in the partition.
    //          They will have no idea where the Level 0 BDT is, and cannot properly handle the interrupt.
    //          So - the invoking processor stores the absolute address of the level 0 BDT in the mail slots
    //          for the various IPs, and the UPI handler code in the IP reads that, and sets the Level 0 BDT
    //          register accordingly before raising the Initial (class 30) interrupt.

    private static SystemProcessor _instance = null;


    //  ------------------------------------------------------------------------
    //  Constructor
    //  ------------------------------------------------------------------------

    /**
     * constructor
     * @param name node name of the SP
     */
    SystemProcessor(
        final String name
    ) {
        super(Type.SystemProcessor, name, InventoryManager.FIRST_SYSTEM_PROCESSOR_UPI_INDEX);
        synchronized (SystemProcessor.class) {
            LOGGER.error("Attempted to instantiate more than one SystemProcessor");
            assert(_instance == null);
            _instance = this;
        }
    }

    /**
     * constructor for testing
     */
    public SystemProcessor() {
        super(Type.SystemProcessor, "SP0", InventoryManager.FIRST_SYSTEM_PROCESSOR_UPI_INDEX);
        _instance = this;
    }

    /**
     * Retrieve singleton instance
     */
    public static SystemProcessor getInstance() {
        return _instance;
    }


    //  ------------------------------------------------------------------------
    //  Private methods
    //  ------------------------------------------------------------------------

    /**
     * Establishes and populates a communications area in one of the configured MSPs.
     * Should be invoked after clearing the various processors and before IPL.
     * The format of the communications area is as follows:
     *      +----------+----------+----------+----------+----------+----------+
     * +0   |          |          |          |            #Entries            |
     *      +----------+----------+----------+----------+----------+----------+
     *      |                           First Entry                           |
     * +1   |  SOURCE  |   DEST   |          |          |          |          |
     *      +----------+----------+----------+----------+----------+----------+
     * +2   |                           First Entry                           |
     * +3   |       Area for communications from source to destination        |
     *      +----------+----------+----------+----------+----------+----------+
     *      |                       Subsequent Entries                        |
     *      |                               ...                               |
     *      +----------+----------+----------+----------+----------+----------+
     * #ENTRIES:  Number of 3-word entries in the table.
     * SOURCE:    UPI Index of processor sending the interrupt
     * DEST:      UPI Index of processor to which the interrupt is sent
     *
     * It should be noted that not every combination of UPI index pairs are necessary,
     * as not all possible paths between types of processors are supported, or implemented.
     * Specifically, we allow interrupts from SPs and IPs to IOPs, as well as the reverse,
     * and we allow interrupts from SPs to IPs and the reverse.
     */
    //TODO should this whole area just be part of the partition data bank?
    private void establishCommunicationsArea(
        final MainStorageProcessor msp,
        final int segment,
        final int offset
    ) throws AddressingExceptionInterrupt {
        //  How many communications slots do we need to create?
        List<Processor> processors = InventoryManager.getInstance().getProcessors();

        int iopCount = 0;
        int ipCount = 0;
        int spCount = 0;

        for (Processor processor : processors) {
            switch (processor._Type) {
                case InputOutputProcessor:
                    iopCount++;
                    break;
                case InstructionProcessor:
                    ipCount++;
                    break;
                case SystemProcessor:
                    spCount++;
                    break;
            }
        }

        //  slots from IPs and SPs to IOPs, and back
        int entries = 2 * (ipCount + spCount) * iopCount;

        //  slots from SPs to IPs
        entries += 2 * spCount * ipCount;

        int size = 1 + (3 * entries);
        ArraySlice commsArea = new ArraySlice(msp.getStorage(segment), offset, size);
        commsArea.clear();

        commsArea.set(0, entries);
        int ax = 1;

        for (Processor source : processors) {
            if ((source._Type == Type.InstructionProcessor)
                || (source._Type == Type.SystemProcessor)) {
                for (Processor destination : processors) {
                    if (destination._Type == Type.InputOutputProcessor) {
                        Word36 w = new Word36();
                        w.setS1(source._upiIndex);
                        w.setS2(destination._upiIndex);
                        commsArea.set(ax, w.getW());
                        ax += 3;

                        w.setS1(destination._upiIndex);
                        w.setS2(source._upiIndex);
                        commsArea.set(ax, w.getW());
                        ax += 3;
                    } else if ((source._Type == Type.SystemProcessor)
                               && (destination._Type == Type.InstructionProcessor)) {
                        Word36 w = new Word36();
                        w.setS1(source._upiIndex);
                        w.setS2(destination._upiIndex);
                        commsArea.set(ax, w.getW());
                        ax += 3;

                        w.setS1(destination._upiIndex);
                        w.setS2(source._upiIndex);
                        commsArea.set(ax, w.getW());
                        ax += 3;
                    }
                }
            }
        }
    }


    //  ------------------------------------------------------------------------
    //  Implementations / overrides of abstract base methods
    //  ------------------------------------------------------------------------

    /**
     * Clears the processor - actually, we never get cleared
     */
    @Override public void clear() {}

    /**
     * SPs have no ancestors
     * @param ancestor candidate ancestor
     * @return always false
     */
    @Override public final boolean canConnect(Node ancestor) { return false; }

    /**
     * For debugging
     * @param writer destination for output
     */
    @Override
    public void dump(
        final BufferedWriter writer
    ) {
        super.dump(writer);
    }

    /**
     * This is the running thread
     */
    @Override
    public void run() {
        LOGGER.info(_name + " worker thread starting");

        while (!_workerTerminate) {
            boolean wait = true;

            //  Check UPI ACKs and SENDs
            //  ACKs mean we can send another IO
            synchronized (_upiPendingAcknowledgements) {
                for (Processor source : _upiPendingAcknowledgements) {
                    //TODO
                    LOGGER.error(String.format("%s received a UPI ACK from %s", _name, source._name));
                    wait = false;
                }
                _upiPendingAcknowledgements.clear();
            }

            //  SENDs mean an IO is completed
            synchronized (_upiPendingInterrupts) {
                for (Processor source : _upiPendingInterrupts) {
                    //TODO
                    LOGGER.error(String.format("%s received a UPI interrupt from %s", _name, source._name));
                    wait = false;
                }
                _upiPendingInterrupts.clear();
            }

            if (wait) {
                try {
                    synchronized (this) {
                        wait(25);
                    }
                } catch (InterruptedException ex) {
                    LOGGER.catching(ex);
                }
            }
        }

        LOGGER.info(_name + " worker thread terminating");
    }


    //  ------------------------------------------------------------------------
    //  Public methods
    //  ------------------------------------------------------------------------

    /**
     * SystemConsole calls here whenever it has input for us - we hold onto it until the OS asks for it
     */
    @Override
    public void notify(
        final String inputText
    ) {

    }


    //  ------------------------------------------------------------------------
    //  Public methods
    //  ------------------------------------------------------------------------

    /**
     * Represents console input
     */
    static class ConsoleInput {
        public final long _identifier;  //  zero for unsolicited input, messageIdentifier from SendReadReply if response
        public final String _message;

        ConsoleInput(
            final long identifier,
            final String message
        ) {
            _identifier = identifier;
            _message = message;
        }

        ConsoleInput(
            final String message
        ) {
            _identifier = 0;
            _message = message;
        }
    }

    ConsoleInput consolePoll() {
        return null;//TODO
    }

    void consoleReset() {
        //TODO
    }

    void consoleSendReadOnlyMessage(
        final long messageIdentifier,
        final String message
    ) {
        //TODO
    }

    void consoleSendReadReplyMessage(
        final long messageIdentifier,
        final String message,
        final int replyMaxCharacters
    ) {
        //TODO
    }

    void consoleSendSystemMessage(
        final String message1,
        final String message2
    ) {
        //TODO
    }
}
