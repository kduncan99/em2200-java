/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib;

import com.kadware.komodo.baselib.Credentials;
import com.kadware.komodo.baselib.KomodoLoggingAppender;
import com.kadware.komodo.baselib.Word36;
import java.io.BufferedWriter;
import java.time.Instant;
import java.time.temporal.ChronoField;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.message.EntryMessage;

//TODO move this commentary somewhere else, where it makes sense
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
//  Notes:
//      Loader code must know how many OS banks exist, and how big they are
//      The loader is responsible for creating the Level 0 BDT (at a minimum)
//      At some point, the loader must send UPI interrupts to the other IPs in the partition.
//          They will have no idea where the Level 0 BDT is, and cannot properly handle the interrupt.
//          So - the invoking processor stores the absolute address of the level 0 BDT in the mail slots
//          for the various IPs, and the UPI handler code in the IP reads that, and sets the Level 0 BDT
//          register accordingly before raising the Initial (class 30) interrupt.


/**
 * Class which implements the functionality necessary for an architecturally defined system control facility.
 * Our design stipulates the existence of exactly one of these in a proper configuration.
 * This object manages all console communication, and implements the system-wide dayclock.
 * It is also responsible for creating and managing the partition data bank which is used by the operating system.
 */
@SuppressWarnings("Duplicates")
public class SystemProcessor extends Processor implements JumpKeyPanel {

    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Data
    //  ----------------------------------------------------------------------------------------------------------------------------

    private static final long LOG_PERIODICITY_MSECS = 1000;             //  check the log every 1 second

    private static final Logger LOGGER = LogManager.getLogger(SystemProcessor.class.getSimpleName());

    private KomodoLoggingAppender _appender;            //  Log appender, so we can catch log entries
    private SystemProcessorInterface _systemConsoleInterface;
    Credentials _credentials;                           //  Current admin credentials for logging into the SPIF
    private long _dayclockComparatorMicros;             //  compared against emulator time to decide whether to cause interrupt
    private long _dayclockOffsetMicros = 0;             //  applied to host system time in micros, to obtain emulator time
    private Integer _httpPort = null;
    private Integer _httpsPort = null;
    private long _jumpKeys = 0;
    private long _mostRecentLogIdentifier = 0;


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Constructor
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * constructor for SPs with an HTTPSystemProcessorInterface (currently, that's all we have)
     * @param name node name of the SP
     * @param httpPort port number for HTTP interface - null if we don't want HTTP
     * @param httpsPort port number for HTTPS interface - null if we don't want HTTPS
     */
    SystemProcessor(
        final String name,
        final Integer httpPort,
        final Integer httpsPort,
        final Credentials credentials
    ) {
        super(ProcessorType.SystemProcessor, name, InventoryManager.FIRST_SYSTEM_PROCESSOR_UPI_INDEX);
        _httpPort = httpPort;
        _httpsPort = httpsPort;
        _credentials = credentials;

        _appender = KomodoLoggingAppender.create();
        LoggerContext logContext = (LoggerContext) LogManager.getContext(false);
        org.apache.logging.log4j.core.config.Configurator.setAllLevels(LogManager.getRootLogger().getName(), Level.ALL);
        logContext.updateLoggers();
    }

    /**
     * constructor for testing
     */
    public SystemProcessor() {
        super(ProcessorType.SystemProcessor, "SP0", InventoryManager.FIRST_SYSTEM_PROCESSOR_UPI_INDEX);
    }

    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Private methods
    //  ----------------------------------------------------------------------------------------------------------------------------

//    /**
//     * Establishes and populates a communications area in one of the configured MSPs.
//     * Should be invoked after clearing the various processors and before IPL.
//     * The format of the communications area is as follows:
//     *      +----------+----------+----------+----------+----------+----------+
//     * +0   |          |          |          |            #Entries            |
//     *      +----------+----------+----------+----------+----------+----------+
//     *      |                           First Entry                           |
//     * +1   |  SOURCE  |   DEST   |          |          |          |          |
//     *      +----------+----------+----------+----------+----------+----------+
//     * +2   |                           First Entry                           |
//     * +3   |       Area for communications from source to destination        |
//     *      +----------+----------+----------+----------+----------+----------+
//     *      |                       Subsequent Entries                        |
//     *      |                               ...                               |
//     *      +----------+----------+----------+----------+----------+----------+
//     * #ENTRIES:  Number of 3-word entries in the table.
//     * SOURCE:    UPI Index of processor sending the interrupt
//     * DEST:      UPI Index of processor to which the interrupt is sent
//     *
//     * It should be noted that not every combination of UPI index pairs are necessary,
//     * as not all possible paths between types of processors are supported, or implemented.
//     * Specifically, we allow interrupts from SPs and IPs to IOPs, as well as the reverse,
//     * and we allow interrupts from SPs to IPs and the reverse.
//     */
//    //TODO should this whole area just be part of the partition data bank?
//    private void establishCommunicationsArea(
//        final MainStorageProcessor msp,
//        final int segment,
//        final int offset
//    ) throws AddressingExceptionInterrupt {
//        //  How many communications slots do we need to create?
//        List<Processor> processors = InventoryManager.getInstance().getProcessors();
//
//        int iopCount = 0;
//        int ipCount = 0;
//        int spCount = 0;
//
//        for (Processor processor : processors) {
//            switch (processor._Type) {
//                case InputOutputProcessor:
//                    iopCount++;
//                    break;
//                case InstructionProcessor:
//                    ipCount++;
//                    break;
//                case SystemProcessor:
//                    spCount++;
//                    break;
//            }
//        }
//
//        //  slots from IPs and SPs to IOPs, and back
//        int entries = 2 * (ipCount + spCount) * iopCount;
//
//        //  slots from SPs to IPs
//        entries += 2 * spCount * ipCount;
//
//        int size = 1 + (3 * entries);
//        ArraySlice commsArea = new ArraySlice(msp.getStorage(segment), offset, size);
//        commsArea.clear();
//
//        commsArea.set(0, entries);
//        int ax = 1;
//
//        for (Processor source : processors) {
//            if ((source._Type == ProcessorType.InstructionProcessor)
//                || (source._Type == ProcessorType.SystemProcessor)) {
//                for (Processor destination : processors) {
//                    if (destination._Type == ProcessorType.InputOutputProcessor) {
//                        Word36 w = new Word36();
//                        w.setS1(source._upiIndex);
//                        w.setS2(destination._upiIndex);
//                        commsArea.set(ax, w.getW());
//                        ax += 3;
//
//                        w.setS1(destination._upiIndex);
//                        w.setS2(source._upiIndex);
//                        commsArea.set(ax, w.getW());
//                        ax += 3;
//                    } else if ((source._Type == ProcessorType.SystemProcessor)
//                               && (destination._Type == ProcessorType.InstructionProcessor)) {
//                        Word36 w = new Word36();
//                        w.setS1(source._upiIndex);
//                        w.setS2(destination._upiIndex);
//                        commsArea.set(ax, w.getW());
//                        ax += 3;
//
//                        w.setS1(destination._upiIndex);
//                        w.setS2(source._upiIndex);
//                        commsArea.set(ax, w.getW());
//                        ax += 3;
//                    }
//                }
//            }
//        }
//    }

    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Implementations / overrides of abstract base methods
    //  ----------------------------------------------------------------------------------------------------------------------------

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


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Implementations / overrides of JumpKeyPanel
    //  ----------------------------------------------------------------------------------------------------------------------------

    @Override
    public boolean getJumpKey(
        final int jumpKeyId
    ) {
        if ((jumpKeyId > 0) && (jumpKeyId < 37)) {
            long mask = 1L << (36 - jumpKeyId);
            return (_jumpKeys & mask) != 0;
        }
        return false;
    }

    @Override
    public Word36 getJumpKeys() {
        return new Word36(_jumpKeys);
    }

    @Override
    public void setJumpKey(
        final int jumpKeyId,
        final boolean value
    ) {
        if ((jumpKeyId > 0) && (jumpKeyId < 37)) {
            long mask = 1L << (36 - jumpKeyId);
            if (value) {
                _jumpKeys |= mask;
            } else {
                mask ^= 0_777777_777777L;
                _jumpKeys &= mask;
            }
            _systemConsoleInterface.jumpKeysUpdated();
        }
    }

    @Override
    public void setJumpKeys(
        final Word36 word36
    ) {
        _jumpKeys = word36.getW();
        _systemConsoleInterface.jumpKeysUpdated();
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Async worker thread for the SystemProcessor
    //  ----------------------------------------------------------------------------------------------------------------------------

    @Override
    public void run() {
        _isRunning = true;
        LOGGER.info(_name + " worker thread starting");

        _systemConsoleInterface = new HTTPSystemProcessorInterface(this,
                                                                    _name + "-SPIF",
                                                                   _httpPort,
                                                                   _httpsPort);
        _systemConsoleInterface.start();

        _isReady = true;
        LOGGER.info(_systemConsoleInterface.getName() + " Ready");
        long nextLogCheck = System.currentTimeMillis() + LOG_PERIODICITY_MSECS;
        while (!_workerTerminate) {
            long now = System.currentTimeMillis();
            if (now > nextLogCheck) {
                if (_appender.getMostRecentIdentifier() > _mostRecentLogIdentifier) {
                    KomodoLoggingAppender.LogEntry[] appenderEntries =
                        _appender.retrieveFrom(_mostRecentLogIdentifier + 1);
                    if (appenderEntries.length > 0) {
                        _systemConsoleInterface.postSystemLogEntries(appenderEntries);
                        _mostRecentLogIdentifier = _appender.getMostRecentIdentifier();
                    }
                }
                nextLogCheck += LOG_PERIODICITY_MSECS;
            }

            //  Check UPI ACKs and SENDs
            //  ACKs mean we can send another IO
            boolean didSomething = false;
            synchronized (_upiPendingAcknowledgements) {
                for (Processor source : _upiPendingAcknowledgements) {
                    //TODO
                    LOGGER.error(String.format("%s received a UPI ACK from %s", _name, source._name));
                    didSomething = true;
                }
                _upiPendingAcknowledgements.clear();
            }

            //  SENDs mean an IO is completed
            synchronized (_upiPendingInterrupts) {
                for (Processor source : _upiPendingInterrupts) {
                    //TODO
                    LOGGER.error(String.format("%s received a UPI interrupt from %s", _name, source._name));
                    didSomething = true;
                }
                _upiPendingInterrupts.clear();
            }

            if (!didSomething) {
                try {
                    synchronized (this) {
                        wait(25);
                    }
                } catch (InterruptedException ex) {
                    LOGGER.catching(ex);
                }
            }
        }

        _systemConsoleInterface.stop();
        _systemConsoleInterface = null;
        LOGGER.info(_name + " worker thread terminating");
        _isReady = false;
        _isRunning = false;
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Public methods - for other processors to invoke
    //  Mostly for InstructionProcessor's SYSC instruction
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * Cancels a previously-sent read-reply message, and optionally replaces the previous message with new text
     */
    void consoleCancelReadReplyMessage(
        final int consoleId,
        final int messageId,
        final String replacementText
    ) {
        EntryMessage em = LOGGER.traceEntry("{}.{}(consoleId=%d messageId=%d text='%s')",
                                            this.getClass().getSimpleName(),
                                            "consoleCancelReadReplyMessage",
                                            consoleId,
                                            messageId,
                                            replacementText);
        _systemConsoleInterface.cancelReadReplyMessage(consoleId, messageId, replacementText);
    }

    /**
     * Reads input from the system console.
     * If no input is available, the return value is null.
     * If input is available, unsolicited input is returned with a single leading blank,
     * while responses to read-reply messages are returned in the format {n}{s} where {n} is the ASCII
     * representation of the message id followed by the text (if any).
     */
    SystemProcessorInterface.ConsoleInputMessage consolePollInputMessage(
        final long waitMilliseconds
    ) {
        EntryMessage em = LOGGER.traceEntry("{}.{}(waitMilliseconds=%d)",
                                            this.getClass().getSimpleName(),
                                            "consolePollInputMessage",
                                            waitMilliseconds);
        SystemProcessorInterface.ConsoleInputMessage result = _systemConsoleInterface.pollInputMessage(waitMilliseconds);
        LOGGER.traceExit(em, result);
        return result;
    }

    /**
     * Resets the conceptual system console
     */
    void consoleReset() {
        EntryMessage em = LOGGER.traceEntry("{}.{}()",
                                            this.getClass().getSimpleName(),
                                            "consoleReset");
        _systemConsoleInterface.reset();
        LOGGER.traceExit(em);
    }

    /**
     * Sends a read-only output message to the conceptual system console.
     * The message may be padded or truncated to an appropriate size.
     * It may be generated by the OS via the SYSC instruction.
     * @param message actual message to be sent
     * @param rightJustified true if this message is to appear right-justified
     * @param cached true to cache this for future new console sessions
     */
    void consoleSendReadOnlyMessage(
        final int consoleId,
        final String message,
        final Boolean rightJustified,
        final Boolean cached
    ) {
        EntryMessage em = LOGGER.traceEntry("{}.{}(consoleId=%d message='%s' rightJust=%s cached=%s)",
                                            this.getClass().getSimpleName(),
                                            "consoleSendReadOnlyMessage",
                                            consoleId,
                                            message,
                                            rightJustified,
                                            cached);
        _systemConsoleInterface.postReadOnlyMessage(consoleId, message, rightJustified, cached);
        LOGGER.traceExit(em);
    }

    /**
     * Convenience wrapper for the above...
     */
    void consoleSendReadOnlyMessage(
        final int consoleId,
        final String message
    ) {
        EntryMessage em = LOGGER.traceEntry("{}.{}(consoleId=%d message='%s')",
                                            this.getClass().getSimpleName(),
                                            "consoleSendReadOnlyMessage",
                                            consoleId,
                                            message);
        _systemConsoleInterface.postReadOnlyMessage(consoleId, message, false, true);
        LOGGER.traceExit(em);
    }

    /**
     * Sends a read-reply output message to the conceptual system console.
     * The message may be padded or truncated to an appropriate size.
     * It may be generated by the OS via the SYSC instruction.
     * @param message actual message to be sent
     */
    void consoleSendReadReplyMessage(
        final int consoleId,
        final int messageId,
        final String message,
        final int maxReplyLength
    ) {
        EntryMessage em = LOGGER.traceEntry("{}.{}(consoleId=%d messageId=%s message='%s' maxReplyLength=%d)",
                                            this.getClass().getSimpleName(),
                                            "consoleSendReadReplyMessage",
                                            consoleId,
                                            messageId,
                                            message,
                                            maxReplyLength);
        _systemConsoleInterface.postReadReplyMessage(consoleId, messageId, message, maxReplyLength);
        LOGGER.traceExit(em);
    }

    /**
     * Sends a list of status messages to the conceptual system console.
     * The messages may be padded or truncated to an appropriate size.
     * Functions by accepting the message sourced from the OS, and queueing it for eventual delivery via poll response.
     * It may be generated by the OS via the SYSC instruction.
     * Generally there will be exactly two - but implementing consoles should not rely on this
     */
    void consoleSendStatusMessage(
        final String[] messages
    ) {
        EntryMessage em = LOGGER.traceEntry("{}.{}(#messages=%d)",
                                            this.getClass().getSimpleName(),
                                            "consoleSendStatusMessage",
                                            messages.length);
        _systemConsoleInterface.postStatusMessages(messages);
        LOGGER.traceExit(em);
    }

    /**
     * Retrieves the master dayclock time in microseconds since epoch.
     * This time is based on the host system time, offset by a value to allow the emulated system time
     * to differ from the host system.
     */
    long dayclockGetMicros() {
        EntryMessage em = LOGGER.traceEntry("{}.{}()",
                                            this.getClass().getSimpleName(),
                                            "dayclockGetMicros");

        Instant i = Instant.now();
        long instantSeconds = i.getLong(ChronoField.INSTANT_SECONDS);
        long microOfSecond = i.getLong(ChronoField.MICRO_OF_SECOND);
        long systemMicros = (instantSeconds * 1000 * 1000) + microOfSecond;
        long result = systemMicros + _dayclockOffsetMicros;

        LOGGER.traceExit(em, result);
        return result;
    }

    /**
     * Sets the system comparator value which drives dayclock interrupts when they're enabled.
     * This value should always be compared against the value returned by dayclockGetMicros() which
     * applies the system offset - the comparator value is always assumed to be offset by the same amount.
     * BTW: We don't actually do any interrupt instigation, nor care if they're enabled - that is handled by the IPs.
     */
    void dayclockSetComparatorMicros(
        final long value
    ) {
        EntryMessage em = LOGGER.traceEntry("{}.{}()",
                                            this.getClass().getSimpleName(),
                                            "dayclockSetComparatorMicros");

        _dayclockComparatorMicros = value;

        LOGGER.traceExit(em);
    }

    /**
     * Stores the difference between the requested dayclock time in microseconds, and the actual host system time
     * converted to dayclock microseconds.  Subsequent dayclock reads must apply this offset.
     */
    void dayclockSetMicros(
        final long value
    ) {
        EntryMessage em = LOGGER.traceEntry("{}.{}()",
                                            this.getClass().getSimpleName(),
                                            "dayclockSetMicros");

        Instant i = Instant.now();
        long instantSeconds = i.getLong(ChronoField.INSTANT_SECONDS);
        long microOfSecond = i.getLong(ChronoField.MICRO_OF_SECOND);
        long systemMicros = (instantSeconds * 1000 * 1000) + microOfSecond;
        _dayclockOffsetMicros = value - systemMicros;

        LOGGER.traceExit(em);
    }

    /**
     * Updates the credentials required for using the SPIF
     * @param credentials new credentials
     */
    void setCredentials(
        final Credentials credentials
    ) {
        EntryMessage em = LOGGER.traceEntry("{}.{}()",
                                            this.getClass().getSimpleName(),
                                            "setCredentials");
        _credentials = credentials;
        LOGGER.traceExit(em);
    }

    /**
     * Stops the current HTTP listener (if any), sets the new port number, and attempts to restart it.
     * Only applies to SPs with an HTTPSystemControlInterface.
     * @param httpPort new http port, 0 to disable
     */
    boolean setHttpPort(
        final int httpPort
    ) {
        EntryMessage em = LOGGER.traceEntry("{}.{}(httpPort=%d)",
                                            this.getClass().getSimpleName(),
                                            "setHttpPort",
                                            httpPort);

        boolean result = false;
        _httpPort = httpPort;
        if (_systemConsoleInterface instanceof HTTPSystemProcessorInterface) {
            result = ((HTTPSystemProcessorInterface) _systemConsoleInterface).setNewHttpPort(_httpPort);
        }

        LOGGER.traceExit(em, result);
        return result;
    }

    /**
     * Stops the current HTTP listener (if any), sets the new port number, and attempts to restart it
     * Only applies to SPs with an HTTPSystemControlInterface.
     * @param httpsPort new https port, 0 to disable
     */
    boolean setHttpsPort(
        final int httpsPort
    ) {
        EntryMessage em = LOGGER.traceEntry("{}.{}(httpPort=%d)",
                                            this.getClass().getSimpleName(),
                                            "setHttpsPort",
                                            httpsPort);

        boolean result = false;
        _httpsPort = httpsPort;
        if (_systemConsoleInterface instanceof HTTPSystemProcessorInterface) {
            result = ((HTTPSystemProcessorInterface) _systemConsoleInterface).setNewHttpsPort(_httpsPort);
        }

        LOGGER.traceExit(em, result);
        return result;
    }
}
