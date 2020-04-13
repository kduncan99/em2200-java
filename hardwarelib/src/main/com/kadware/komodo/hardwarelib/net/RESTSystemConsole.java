/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kadware.komodo.baselib.KomodoLoggingAppender;
import com.kadware.komodo.baselib.PathNames;
import com.kadware.komodo.baselib.Word36;
import com.kadware.komodo.baselib.HttpMethod;
import com.kadware.komodo.baselib.SecureServer;
import com.kadware.komodo.hardwarelib.SoftwareConfiguration;
import com.kadware.komodo.hardwarelib.SystemConsole;
import com.kadware.komodo.hardwarelib.SystemProcessor;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Class which implements the functionality necessary for a system console.
 * This variation implements a REST server interface, providing all the functionality required of a system console
 * via HTTP / HTTPS REST methods (i.e., DELETE, GET, POST, PUT).
 * Our design provides for multiple clients, but which are not visible as such, to the operating system, which
 * 'sees' our client(s) as one console.
 *
 * This implementation uses long polling.
 */
@SuppressWarnings("Duplicates")
public class RESTSystemConsole implements SystemConsole {

    //  ----------------------------------------------------------------------------------------------------------------------------
    //  interface for anonymous-class-based client notification
    //  ----------------------------------------------------------------------------------------------------------------------------

    private interface PokeClientFunction {
        void function(final ClientInfo clientInfo);
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Information local to each established session
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * ClientInfo - information regarding a particular client
     */
    private static class ClientInfo {

        private final String _clientId;
        private long _lastActivity = System.currentTimeMillis();
        private boolean _isMaster = false;
        private InetSocketAddress _remoteAddress = null;

        //  notification-ish things
        public boolean _inputDelivered = false;
        public boolean _isMasterChanged = false;
        public List<KomodoLoggingAppender.LogEntry> _pendingLogEntries = new LinkedList<>();
        public List<ReadOnlyMessage> _pendingReadOnlyMessages = new LinkedList<>();
        public boolean _updatedHardwareConfiguration = false;
        public boolean _updatedJumpKeys = false;
        public boolean _updatedReadReplyMessages = false;
        public boolean _updatedStatusMessage = false;
        public boolean _updatedSystemConfiguration = false;

        ClientInfo(
            final String clientId
        ) {
            _clientId = clientId;
        }

        public void clear() {
            _inputDelivered = false;
            _isMasterChanged = false;
            _pendingLogEntries.clear();
            _pendingReadOnlyMessages.clear();
            _updatedHardwareConfiguration = false;
            _updatedJumpKeys = false;
            _updatedReadReplyMessages = false;
            _updatedStatusMessage = false;
            _updatedSystemConfiguration = false;
        }

        public boolean hasUpdatesForClient() {
            return _inputDelivered
                || _isMasterChanged
                || !_pendingLogEntries.isEmpty()
                || !_pendingReadOnlyMessages.isEmpty()
                || _updatedHardwareConfiguration
                || _updatedJumpKeys
                || _updatedReadReplyMessages
                || _updatedStatusMessage
                || _updatedSystemConfiguration;
        }
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Data
    //  ----------------------------------------------------------------------------------------------------------------------------

    private static final long CLIENT_AGE_OUT_MSECS = 10 * 60 * 1000;        //  10 minutes of no polling ages out a client
    private static final String HTML_FILE_NAME = "systemConsole/systemConsole.html";
    private static final String FAVICON_FILE_NAME = "systemConsole/favicon.png";
    private static final long POLL_WAIT_MSECS = 10000;                      //  10 second (maximum) poll delay
    private static final int MAX_RECENT_LOG_ENTRIES = 200;                  //  max size of most-recent log entries
    private static final int MAX_RECENT_READ_ONLY_MESSAGES = 30;            //  max size of container of most-recent RO messages
    private static final long WORKER_PERIODICITY_MSECS = 10000;             //  worker thread does its work every 10 seconds

    private static final String[] _logReportingBlackList = { SystemProcessor.class.getName(),
                                                             RESTSystemConsole.class.getName() };

    private static final Logger LOGGER = LogManager.getLogger(RESTSystemConsole.class);

    private final Map<String, ClientInfo> _clientInfos = new HashMap<>();
    private final Listener _listener;
    private final String _name;
    private final String _webDirectory;
    private WorkerThread _workerThread = null;

    //  This is always the latest status message. Clients may pick it up at any time.
    private StatusMessage _latestStatusMessage = null;

    //  Most recent {n} log entries, so we can populate new sessions
    private final List<KomodoLoggingAppender.LogEntry> _recentLogEntries = new LinkedList<>();

    //  Input messages we've received from the console, but which have not yet been delivered to the operating system.
    private final Map<String, InputMessage> _pendingInputMessages = new LinkedHashMap<>();

    //  ReadReply messages which have not yet been replied to
    private final Map<Integer, ReadReplyMessage> _pendingReadReplyMessages = new HashMap<>();

    //  Recent output messages. We preserve a certain number of these so that they can be redisplayed if necessary
    private final Queue<ReadOnlyMessage> _recentReadOnlyMessages = new LinkedList<>() {};


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Constructor
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * constructor
     * @param name node name of the SP
     */
    public RESTSystemConsole(
        final String name
    ) {
        _name = name;
        _listener = new Listener(443);
        _webDirectory = PathNames.RESOURCES_ROOT_DIRECTORY + "web/";
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Abstract classes for the various handlers and handler threads
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * One of these exists per endpoint - the implementing class is endpoint-specific,
     * and knows how to construct an endpoint-specific handler thread to handle each HTTP request for the endpoint.
     */
    private static abstract class DelegatingHttpHandler implements HttpHandler {

        public void handle(
            final HttpExchange exchange
        ) {
            LOGGER.traceEntry(String.format("<-- %s %s", exchange.getRequestMethod(), exchange.getRequestURI()));
            delegate(exchange);
        }

        public abstract void delegate(
            final HttpExchange exchange
        );
    }

    /**
     * We get a new one of these for every HTTP request that comes in.
     */
    private abstract class HandlerThread extends Thread {

        final HttpExchange _exchange;
        final InputStream _requestBody;
        final Headers _requestHeaders;
        final String _requestMethod;

        public HandlerThread(
            final HttpExchange exchange
        ) {
            _exchange = exchange;
            _requestBody = _exchange.getRequestBody();
            _requestHeaders = _exchange.getRequestHeaders();
            _requestMethod = _exchange.getRequestMethod();
        }

        @Override
        public abstract void run();

        /**
         * Checks the headers for a client id, then locates the corresponding ClientInfo object.
         * Returns null if ClientInfo object is not found or is unspecified.
         * Serves as validation for clients which have presumably previously done a POST to /session
         */
        ClientInfo findClient(
        ) {
            List<String> values = _requestHeaders.get("Client");
            if ((values != null) && (values.size() == 1)) {
                String clientId = values.get(0);
                synchronized (_clientInfos) {
                    ClientInfo clientInfo = _clientInfos.get(clientId);
                    if (clientInfo != null) {
                        return clientInfo;
                    }
                }
            }

            return null;
        }

        /**
         * For debugging
         */
        public String getStackTrace(
            final Throwable t
        ) {
            StringBuilder sb = new StringBuilder();
            sb.append(t.toString());
            sb.append("\n");
            for (StackTraceElement e : t.getStackTrace()) {
                sb.append(e.toString());
                sb.append("\n");
            }
            return sb.toString();
        }

        /**
         * Convenient method for handling the situation where a method is requested which is not supported on the endpoint
         */
        void respondBadMethod() {
            String response = String.format("Method %s is not supported for the given endpoint\n", _requestMethod);
            respondWithText(HttpURLConnection.HTTP_BAD_METHOD, response);
        }

        /**
         * Convenient method for handling the situation where a particular request was in error.
         */
        void respondBadRequest(
            final String explanation
        ) {
            respondWithText(HttpURLConnection.HTTP_BAD_REQUEST, explanation + "\n");
        }

        /**
         * Convenient method for handling the situation where no session exists
         */
        void respondNoSession() {
            String response = "Forbidden - session not established\n";
            respondWithText(HttpURLConnection.HTTP_FORBIDDEN, response);
        }

        /**
         * Convenient method for handling the situation where we cannot find something which was requested by the client.
         * This is NOT the same thing as not finding something which definitely should be there.
         */
        void respondNotFound(
            final String message
        ) {
            respondWithText(java.net.HttpURLConnection.HTTP_NOT_FOUND, message + "\n");
        }

        /**
         * Convenient method for handling an internal server error
         */
        void respondServerError(
            final String message
        ) {
            respondWithText(HttpURLConnection.HTTP_INTERNAL_ERROR, message + "\n");
        }

        /**
         * Convenient method for setting up a 401 response
         */
        void respondUnauthorized() {
            String response = "Unauthorized - credentials not provided or are invalid\nPlease enter credentials\n";
            respondWithText(HttpURLConnection.HTTP_UNAUTHORIZED, response);
        }

        /**
         * For responding to the client with the content of a binary file
         */
        void respondWithBinaryFile(
            final int code,
            final String mimeType,
            final String fileName
        ) {
            LOGGER.traceEntry(String.format("respondWithBinaryFile - code:%d mimeType:%s fileName:%s", code, mimeType, fileName));
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(Paths.get(fileName));
            } catch (IOException ex) {
                LOGGER.catching(ex);
                bytes = String.format("Cannot find requested file '%s'", fileName).getBytes();
                try {
                    _exchange.sendResponseHeaders(code, bytes.length);
                    OutputStream os = _exchange.getResponseBody();
                    os.write(bytes);
                    _exchange.close();
                    return;
                } catch (IOException ex2) {
                    LOGGER.catching(ex2);
                    _exchange.close();
                    return;
                }
            }

            _exchange.getResponseHeaders().add("content-type", mimeType);
            _exchange.getResponseHeaders().add("Cache-Control", "no-store");
            try {
                System.out.println(String.format("BYTES len = %d", bytes.length));//TODO
                _exchange.sendResponseHeaders(code, bytes.length);
                OutputStream os = _exchange.getResponseBody();
                os.write(bytes);
            } catch (Exception ex) {
                LOGGER.catching(ex);
            } finally {
                _exchange.close();
            }
        }

        /**
         * Convenient method for sending responses containing JSON
         * @param code The response code - 200, 201, 403, etc - most responses >= 300 won't necessarily have a JSON formatted body
         */
        void respondWithJSON(
            final int code,
            final Object object
        ) {
            LOGGER.traceEntry(String.format("code:%d object:%s", code, object.toString()));
            try {
                ObjectMapper mapper = new ObjectMapper();
                String content = mapper.writeValueAsString(object);
                System.out.println("-->" + content);   //TODO remove
                LOGGER.trace(String.format("  JSON:%s", content));
                _exchange.getResponseHeaders().add("Content-type", "application/json");
                _exchange.getResponseHeaders().add("Cache-Control", "no-store");
                _exchange.sendResponseHeaders(code, content.length());
                OutputStream os = _exchange.getResponseBody();
                os.write(content.getBytes());
                os.close();
            } catch (IOException ex) {
                LOGGER.catching(ex);
                _exchange.close();
            }
        }

        /**
         * Convenient method for sending responses containing straight text
         * @param code The response code - 200, 201, 403, etc
         */
        void respondWithText(
            final int code,
            final String content
        ) {
            LOGGER.traceEntry(String.format("code:%d content:%s", code, content));
            System.out.println("-->" + content);   //TODO remove

            _exchange.getResponseHeaders().add("Content-type", "text/plain");
            byte[] bytes = content.getBytes();

            try {
                _exchange.sendResponseHeaders(code, bytes.length);
                OutputStream os = _exchange.getResponseBody();
                os.write(bytes);
                os.close();
            } catch (IOException ex) {
                LOGGER.catching(ex);
                _exchange.close();
            }
        }

        /**
         * When we need to send back a text file
         */
        void respondWithTextFile(
            final int code,
            final String mimeType,
            final String fileName
        ) {
            LOGGER.traceEntry(String.format("respondWithTextFile - code:%d mimeType:%s fileName:%s", code, mimeType, fileName));
            List<String> textLines;
            try {
                textLines = Files.readAllLines(Paths.get(fileName), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                LOGGER.catching(ex);
                byte[] bytes = String.format("Cannot find requested file '%s'", fileName).getBytes();
                try {
                    _exchange.sendResponseHeaders(code, bytes.length);
                    OutputStream os = _exchange.getResponseBody();
                    os.write(bytes);
                    return;
                } catch (IOException ex2) {
                    LOGGER.catching(ex2);
                    _exchange.close();
                    return;
                }
            }

            byte[] bytes = String.join("\r\n", textLines).getBytes();
            _exchange.getResponseHeaders().add("content-type", mimeType);
            _exchange.getResponseHeaders().add("Cache-Control", "no-store");
            try {
                _exchange.sendResponseHeaders(code, bytes.length);
                OutputStream os = _exchange.getResponseBody();
                os.write(bytes);
            } catch (Exception ex) {
                LOGGER.catching(ex);
            } finally {
                _exchange.close();
            }
        }

        /**
         * Validate the credentials in the header of the given exchange object.
         * Only for POST to /session.
         * @return true if credentials are valid, else false
         */
        boolean validateCredentials() {
            List<String> values = _requestHeaders.get("Authorization");
            if ((values != null) && (values.size() == 1)) {
                String[] split = values.get(0).split(" ");
                if (split.length == 2) {
                    if (split[0].equalsIgnoreCase("Basic")) {
                        String unBased = new String(Base64.getDecoder().decode(split[1]));
                        String[] unBasedSplit = unBased.split(":");
                        if (unBasedSplit.length == 2) {
                            String givenUserName = unBasedSplit[0];
                            String givenClearTextPassword = unBasedSplit[1];
                            SystemProcessor sp = SystemProcessor.getInstance();
                            SoftwareConfiguration sc = sp.getSoftwareConfiguration();
                            if (givenUserName.equalsIgnoreCase(sc._adminCredentials._userName)) {
                                return sc._adminCredentials.validatePassword(givenClearTextPassword);
                            }
                        }
                    }
                }
            }

            return false;
        }
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Endpoint handlers, to be attached to the HTTP listeners
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * Handles requests against the /jumpkeys path
     * GET retrieves the current settings as a WORD36 wrapped in a long.
     * PUT accepts the JK settings as a WORD36 wrapped in a long, and persists them to the singular system jump key panel.
     * Either way, JK36 is in the least-significant bit and JKn is 36-n bits to the left of the LSB.
     */
    private class APIJumpKeysHandler extends DelegatingHttpHandler {

        public void delegate(
            final HttpExchange exchange
        ) {
            Thread t = new LocalThread(exchange);
            t.start();
        }

        private class LocalThread extends HandlerThread {

            LocalThread(
                final HttpExchange exchange
            ) {
                super(exchange);
            }

            private JumpKeys createJumpKeys(
                final long compositeValue
            ) {
                HashMap<Integer, Boolean> map = new HashMap<>();
                long bitMask = 0_400000_000000L;
                for (int jkid = 1; jkid <= 36; jkid++) {
                    map.put(jkid, (compositeValue & bitMask) != 0);
                    bitMask >>= 1;
                }

                return new JumpKeys(compositeValue, map);
            }

            @Override
            public void run() {
                try {
                    ClientInfo clientInfo = findClient();
                    if (clientInfo == null) {
                        respondNoSession();
                        return;
                    }

                    clientInfo._lastActivity = System.currentTimeMillis();

                    //  For GET - return the settings as both a composite value and a map of individual jump key settings
                    if (_requestMethod.equalsIgnoreCase(HttpMethod.GET._value)) {
                        SystemProcessor sp = SystemProcessor.getInstance();
                        JumpKeys jumpKeysResponse = createJumpKeys(sp.getJumpKeys().getW());
                        respondWithJSON(HttpURLConnection.HTTP_OK, jumpKeysResponse);
                        return;
                    }

                    //  For PUT - accept the input object - if it has a composite value, use that to set the entire jump key panel.
                    //  If it has no composite value, but it has component values, use them to individually set the jump keys.
                    //  If it has neither, reject the PUT.
                    if (_requestMethod.equalsIgnoreCase(HttpMethod.PUT._value)) {
                        SystemProcessor sp = SystemProcessor.getInstance();
                        JumpKeys content;
                        try {
                            content = new ObjectMapper().readValue(_requestBody, JumpKeys.class);
                        } catch (IOException ex) {
                            respondBadRequest(ex.getMessage());
                            return;
                        }

                        if (content._compositeValue != null) {
                            if ((content._compositeValue < 0) || (content._compositeValue > 0_777777_777777L)) {
                                respondBadRequest("Invalid composite value");
                                return;
                            }

                            sp.setJumpKeys(new Word36(content._compositeValue));
                            JumpKeys jumpKeysResponse = createJumpKeys(content._compositeValue);
                            respondWithJSON(HttpURLConnection.HTTP_OK, jumpKeysResponse);
                            return;
                        }

                        if (content._componentValues != null) {
                            for (Map.Entry<Integer, Boolean> entry : content._componentValues.entrySet()) {
                                int jumpKeyId = entry.getKey();
                                if ((jumpKeyId < 1) || (jumpKeyId > 36)) {
                                    respondBadRequest(String.format("Invalid component value jump key id: %d", jumpKeyId));
                                    return;
                                }

                                boolean setting = entry.getValue();
                                sp.setJumpKey(jumpKeyId, setting);

                                JumpKeys jumpKeysResponse = createJumpKeys(sp.getJumpKeys().getW());
                                respondWithJSON(HttpURLConnection.HTTP_OK, jumpKeysResponse);
                                return;
                            }
                        }

                        respondBadRequest("Requires either composite or component values");
                        return;
                    }

                    //  Neither a GET or a PUT - this is not allowed.
                    respondBadMethod();
                } catch (Throwable t) {
                    LOGGER.catching(t);
                    respondServerError(getStackTrace(t));
                }
            }
        }
    }

    /**
     * Provides a method for injecting input to the system via POST to /message
     */
    private class APIMessageHandler extends DelegatingHttpHandler {

        public void delegate(
            final HttpExchange exchange
        ) {
            Thread t = new LocalThread(exchange);
            t.start();
        }

        private class LocalThread extends HandlerThread {

            LocalThread(
                final HttpExchange exchange
            ) {
                super(exchange);
            }

            @Override
            public void run() {
                try {
                    ClientInfo clientInfo = findClient();
                    if (clientInfo == null) {
                        respondNoSession();
                        return;
                    }

                    clientInfo._lastActivity = System.currentTimeMillis();
                    if (!_requestMethod.equals(HttpMethod.POST._value)) {
                        respondBadMethod();
                        return;
                    }

                    boolean collision = false;
                    synchronized (_pendingInputMessages) {
                        if (_pendingInputMessages.containsKey(clientInfo._clientId)) {
                            collision = true;
                        } else {
                            ObjectMapper mapper = new ObjectMapper();
                            ConsoleInputMessage msg = mapper.readValue(_requestBody, ConsoleInputMessage.class);
                            if (msg._messageId != null) {
                                ReadReplyInputMessage rrim = new ReadReplyInputMessage(msg._messageId, msg._text);
                                _pendingInputMessages.put(clientInfo._clientId, rrim);
                            } else {
                                UnsolicitedInputMessage uim = new UnsolicitedInputMessage(msg._text);
                                _pendingInputMessages.put(clientInfo._clientId, uim);
                            }
                        }
                    }

                    if (collision) {
                        respondWithText(HttpURLConnection.HTTP_CONFLICT, "Previous input not yet acknowledged");
                    } else {
                        respondWithText(HttpURLConnection.HTTP_CREATED, "");
                    }
                } catch (IOException ex) {
                    respondBadRequest("Badly-formatted body");
                } catch (Throwable t) {
                    LOGGER.catching(t);
                    respondServerError(getStackTrace(t));
                }
            }
        }
    }

    /**
     * Handle a poll request (a GET to /poll).
     * Check to see if there is anything new.  If so, send it.
     * Otherwise, wait for some period of time to see whether anything new pops up.
     */
    private class APIPollRequestHandler extends DelegatingHttpHandler {

        public void delegate(
            final HttpExchange exchange
        ) {
            Thread t = new LocalThread(exchange);
            t.start();
        }

        private class LocalThread extends HandlerThread {

            LocalThread(
                final HttpExchange exchange
            ) {
                super(exchange);
            }

            @Override
            public void run() {
                try {
                    ClientInfo clientInfo = findClient();
                    if (clientInfo == null) {
                        respondNoSession();
                        return;
                    }

                    clientInfo._lastActivity = System.currentTimeMillis();
                    if (!_requestMethod.equals(HttpMethod.GET._value)) {
                        respondBadMethod();
                        return;
                    }

                    //  Check if there are any updates already waiting for the client to pick up.
                    //  If not, go into a wait loop which will be interrupted if any updates eventuate during the wait.
                    //  At the end of the wait construct and return a SystemProcessorPollResult object
                    synchronized (clientInfo) {
                        if (!clientInfo.hasUpdatesForClient()) {
                            try {
                                clientInfo.wait(POLL_WAIT_MSECS);
                            } catch (InterruptedException ex) {
                                //  do nothing
                            }
                        }

                        Boolean isMaster = null;
                        if (clientInfo._isMasterChanged) {
                            isMaster = clientInfo._isMaster;
                        }

                        Long jumpKeyValue = null;
                        if (clientInfo._updatedJumpKeys) {
                            SystemProcessor sp = SystemProcessor.getInstance();
                            jumpKeyValue = sp.getJumpKeys().getW();
                        }

                        ConsoleStatusMessage latestStatusMessage = null;
                        if (clientInfo._updatedStatusMessage) {
                            latestStatusMessage = new ConsoleStatusMessage(_latestStatusMessage._text);
                        }

                        SystemLogEntry[] newLogEntries = null;
                        if (!clientInfo._pendingLogEntries.isEmpty()) {
                            int entryCount = clientInfo._pendingLogEntries.size();
                            newLogEntries = new SystemLogEntry[entryCount];
                            int ex = 0;
                            for (KomodoLoggingAppender.LogEntry localEntry : clientInfo._pendingLogEntries) {
                                newLogEntries[ex++] = new SystemLogEntry(localEntry._timeMillis,
                                                                         localEntry._category,
                                                                         localEntry._source,
                                                                         localEntry._message);
                            }
                        }

                        ConsoleReadOnlyMessage[] newReadOnlyMessages = null;
                        if (!clientInfo._pendingReadOnlyMessages.isEmpty()) {
                            int msgCount = clientInfo._pendingReadOnlyMessages.size();
                            newReadOnlyMessages = new ConsoleReadOnlyMessage[msgCount];
                            int mx = 0;
                            for (ReadOnlyMessage pendingMsg : clientInfo._pendingReadOnlyMessages) {
                                newReadOnlyMessages[mx++] = new ConsoleReadOnlyMessage(pendingMsg._text);
                            }
                        }

                        if (clientInfo._updatedHardwareConfiguration) {
                            //TODO
                        }

                        if (clientInfo._updatedSystemConfiguration) {
                            //TODO
                        }

                        PollResult pollResult = new PollResult(clientInfo._inputDelivered,
                                                               isMaster,
                                                               jumpKeyValue,
                                                               latestStatusMessage,
                                                               newLogEntries,
                                                               newReadOnlyMessages,
                                                               clientInfo._updatedReadReplyMessages);

                        respondWithJSON(HttpURLConnection.HTTP_OK, pollResult);
                        clientInfo.clear();
                    }
                } catch (Throwable t) {
                    LOGGER.catching(t);
                    respondServerError(getStackTrace(t));
                }
            }
        }
    }

    /**
     * Handle posts to /session
     * Validates credentials and method
     * Creates and stashes a ClientInfo record for future method calls
     */
    private class APISessionRequestHandler extends DelegatingHttpHandler {

        public void delegate(
            final HttpExchange exchange
        ) {
            Thread t = new LocalThread(exchange);
            t.start();
        }

        private class LocalThread extends HandlerThread {

            LocalThread(
                final HttpExchange exchange
            ) {
                super(exchange);
            }

            @Override
            public void run() {
                try {
                    if (!validateCredentials()) {
                        respondUnauthorized();
                        return;
                    }

                    if (!_requestMethod.equalsIgnoreCase(HttpMethod.POST._value)) {
                        respondBadMethod();
                        return;
                    }

                    String clientId = UUID.randomUUID().toString();
                    ClientInfo clientInfo = new ClientInfo(clientId);
                    synchronized (_recentReadOnlyMessages) {
                        clientInfo._pendingReadOnlyMessages.addAll(_recentReadOnlyMessages);
                    }

                    synchronized (_recentLogEntries) {
                        clientInfo._pendingLogEntries.addAll(_recentLogEntries);
                    }

                    clientInfo._remoteAddress = _exchange.getRemoteAddress();
                    clientInfo._inputDelivered = false;
                    clientInfo._updatedJumpKeys = true;
                    clientInfo._updatedStatusMessage = true;
                    clientInfo._updatedHardwareConfiguration = true;
                    clientInfo._updatedSystemConfiguration = true;
                    synchronized (this) {
                        if (_clientInfos.isEmpty()) {
                            clientInfo._isMaster = true;
                            clientInfo._updatedReadReplyMessages = true;
                        }
                        _clientInfos.put(clientId, clientInfo);
                    }

                    respondWithJSON(HttpURLConnection.HTTP_CREATED, clientId);
                } catch (Throwable t) {
                    LOGGER.catching(t);
                    respondServerError(getStackTrace(t));
                }
            }
        }
    }

    /**
     * Handle all the web endpoint requests
     */
    private class WebHandler extends DelegatingHttpHandler {

        public void delegate(
            final HttpExchange exchange
        ) {
            Thread t = new LocalThread(exchange);
            t.start();
        }

        private class LocalThread extends HandlerThread {

            LocalThread(
                final HttpExchange exchange
            ) {
                super(exchange);
            }

            @Override
            public void run() {
                try {
                    String fileName = _exchange.getRequestURI().getPath();
                    if (fileName.startsWith("/")) {
                        fileName = fileName.substring(1);
                    }
                    if (fileName.isEmpty() || fileName.equalsIgnoreCase("index.html")) {
                        fileName = HTML_FILE_NAME;
                    }

                    String mimeType;
                    boolean textFile = false;
                    if (fileName.contains("favicon.ico")) {
                        fileName = FAVICON_FILE_NAME;
                        mimeType = "image/x-icon";
                    } else {
                        if (fileName.endsWith(".html")) {
                            mimeType = "text/html";
                            textFile = true;
                        } else if (fileName.endsWith(".css")) {
                            mimeType = "text/css";
                            textFile = true;
                        } else if (fileName.endsWith(".bmp")) {
                            mimeType = "image/bmp";
                            textFile = false;
                        } else if (fileName.endsWith(".png")) {
                            mimeType = "image/png";
                            textFile = false;
                        } else if (fileName.endsWith(".js")) {
                            mimeType = "application/javascript";
                            textFile = true;
                        } else if (fileName.endsWith(".json")) {
                            mimeType = "text/json";
                            textFile = true;
                        } else {
                            mimeType = "application/octet-stream";
                        }
                    }

                    String fullName = String.format("%s%s", _webDirectory, fileName);
                    LOGGER.trace("fullName:" + fullName);//TODO remove
                    if (textFile) {
                        respondWithTextFile(HttpURLConnection.HTTP_OK, mimeType, fullName);
                    } else {
                        respondWithBinaryFile(HttpURLConnection.HTTP_OK, mimeType, fullName);
                    }
                } catch (Throwable t) {
                    LOGGER.catching(t);
                }
            }
        }
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  HTTP listener for the API
    //  All requests must use basic authentication for every message (in the headers, of course)
    //  All requests must include a (supposedly) unique UUID as a client identifier in the headers "Client={uuid}"
    //  This unique UUID must be used for every message sent by a given instance of a client.
    //  ----------------------------------------------------------------------------------------------------------------------------

    private class Listener extends SecureServer {

        /**
         * constructor
         */
        private Listener(
            final int portNumber
        ) {
            super("RESTSystemConsole", portNumber);
        }

        /**
         * Client wants us to start accepting requests
         */
        @Override
        public void setup(
        ) throws CertificateException,
                 InvalidKeyException,
                 IOException,
                 KeyManagementException,
                 KeyStoreException,
                 NoSuchAlgorithmException,
                 NoSuchProviderException,
                 SignatureException {
            super.setup();
            appendHandler("/", new WebHandler());
            appendHandler("/jumpkeys", new APIJumpKeysHandler());
            appendHandler("/message", new APIMessageHandler());
            appendHandler("/session", new APISessionRequestHandler());
            appendHandler("/poll", new APIPollRequestHandler());
            start();
        }

        /**
         * Owner wants us to stop accepting requests.
         * Tell our base class to STOP, then go wake up all the pending clients.
         */
        @Override
        public void stop() {
            super.stop();
            pokeClients(ClientInfo::clear);
        }
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Private methods
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * Thread-safe method for invoking a particular function on all established clients, then waking them up
     * @param pokeFunction A (potentially anonymous) class containing a function to be executed for eacah entity - if null, it is ignored
     */
    private void pokeClients(
        final PokeClientFunction pokeFunction
    ) {
        Set<ClientInfo> cinfos;
        synchronized (this) {
            cinfos = new HashSet<>(_clientInfos.values());
        }

        for (ClientInfo cinfo : cinfos) {
            synchronized (cinfo) {
                if (pokeFunction != null) {
                    pokeFunction.function(cinfo);
                }
                cinfo.notify();
            }
        }
    }

    /**
     * Client wants us to age-out any old client info objects
     */
    private void pruneClients() {
        synchronized (_clientInfos) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, ClientInfo>> iter = _clientInfos.entrySet().iterator();
            List<ClientInfo> removedClientInfos = new LinkedList<>();
            while (iter.hasNext()) {
                Map.Entry<String, ClientInfo> entry = iter.next();
                ClientInfo cinfo = entry.getValue();
                if (now > (cinfo._lastActivity + CLIENT_AGE_OUT_MSECS)) {
                    LOGGER.info(String.format("Removing aged-out client %s", cinfo._clientId));
                    iter.remove();
                    removedClientInfos.add(cinfo);
                }
            }

            for (ClientInfo cinfo : removedClientInfos) {
                synchronized (cinfo) {
                    cinfo.clear();
                    cinfo.notify();
                }
            }
        }
    }

//    /**
//     * Convenient method for handling the situation where a method is requested which is not supported on the endpoint
//     */
//    private void respondBadMethod(
//        final HttpExchange exchange
//    ) {
//        String response = String.format("Method %s is not supported for the given endpoint\n",
//                                        exchange.getRequestMethod());
//        respondWithText(exchange, HttpURLConnection.HTTP_BAD_METHOD, response);
//    }
//
//    /**
//     * Convenient method for handling the situation where a particular request was in error.
//     */
//    private void respondBadRequest(
//        final HttpExchange exchange,
//        final String explanation
//    ) {
//        respondWithText(exchange, HttpURLConnection.HTTP_BAD_REQUEST, explanation + "\n");
//    }
//
//    /**
//     * Convenient method for handling the situation where no session exists
//     */
//    private void respondNoSession(
//        final HttpExchange exchange
//    ) {
//        String response = "Forbidden - session not established\n";
//        respondWithText(exchange, HttpURLConnection.HTTP_FORBIDDEN, response);
//    }
//
//    /**
//     * Convenient method for handling the situation where we cannot find something which was requested by the client.
//     * This is NOT the same thing as not finding something which definitely should be there.
//     */
//    private void respondNotFound(
//        final HttpExchange exchange,
//        final String message
//    ) {
//        respondWithText(exchange, java.net.HttpURLConnection.HTTP_NOT_FOUND, message + "\n");
//    }
//
//    /**
//     * Convenient method for handling an internal server error
//     */
//    private void respondServerError(
//        final HttpExchange exchange,
//        final String message
//    ) {
//        respondWithText(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, message + "\n");
//    }
//
//    /**
//     * Convenient method for setting up a 401 response
//     */
//    private void respondUnauthorized(
//        final HttpExchange exchange
//    ) {
//        String response = "Unauthorized - credentials not provided or are invalid\nPlease enter credentials\n";
//        respondWithText(exchange, HttpURLConnection.HTTP_UNAUTHORIZED, response);
//    }
//
//    private void respondWithBinaryFile(
//        final HttpExchange exchange,
//        final int code,
//        final String mimeType,
//        final String fileName
//    ) {
//        LOGGER.traceEntry(String.format("respondWithBinaryFile - code:%d mimeType:%s fileName:%s", code, mimeType, fileName));
//        byte[] bytes;
//        try {
//            bytes = Files.readAllBytes(Paths.get(fileName));
//        } catch (IOException ex) {
//            LOGGER.catching(ex);
//            bytes = String.format("Cannot find requested file '%s'", fileName).getBytes();
//            try {
//                exchange.sendResponseHeaders(code, bytes.length);
//                OutputStream os = exchange.getResponseBody();
//                os.write(bytes);
//                exchange.close();
//                return;
//            } catch (IOException ex2) {
//                LOGGER.catching(ex2);
//                exchange.close();
//                return;
//            }
//        }
//
//        exchange.getResponseHeaders().add("content-type", mimeType);
//        exchange.getResponseHeaders().add("Cache-Control", "no-store");
//        try {
//            System.out.println(String.format("BYTES len = %d", bytes.length));//TODO
//            exchange.sendResponseHeaders(code, bytes.length);
//            OutputStream os = exchange.getResponseBody();
//            os.write(bytes);
//        } catch (Exception ex) {
//            LOGGER.catching(ex);
//        } finally {
//            exchange.close();
//        }
//    }
//
//    /**
//     * When we need to send back a text fild
//     * @param exchange HttpExchange we're dealing with
//     * @param code response code - 200, 201, whatever
//     * @param mimeType e.g., text/html
//     * @param fileName Path/Filename of the file we need to send
//     */
//    private void respondWithTextFile(
//        final HttpExchange exchange,
//        final int code,
//        final String mimeType,
//        final String fileName
//    ) {
//        LOGGER.traceEntry(String.format("respondWithTextFile - code:%d mimeType:%s fileName:%s", code, mimeType, fileName));
//        List<String> textLines;
//        try {
//            textLines = Files.readAllLines(Paths.get(fileName), StandardCharsets.UTF_8);
//        } catch (IOException ex) {
//            LOGGER.catching(ex);
//            byte[] bytes = String.format("Cannot find requested file '%s'", fileName).getBytes();
//            try {
//                exchange.sendResponseHeaders(code, bytes.length);
//                OutputStream os = exchange.getResponseBody();
//                os.write(bytes);
//                return;
//            } catch (IOException ex2) {
//                LOGGER.catching(ex2);
//                exchange.close();
//                return;
//            }
//        }
//
//        byte[] bytes = String.join("\r\n", textLines).getBytes();
//        exchange.getResponseHeaders().add("content-type", mimeType);
//        exchange.getResponseHeaders().add("Cache-Control", "no-store");
//        try {
//            exchange.sendResponseHeaders(code, bytes.length);
//            OutputStream os = exchange.getResponseBody();
//            os.write(bytes);
//        } catch (Exception ex) {
//            LOGGER.catching(ex);
//        } finally {
//            exchange.close();
//        }
//    }
//
//    /**
//     * Convenient method for sending responses containing HTML text
//     * @param exchange The HttpExchance object into which we inject the response
//     * @param code The response code - 200, 201, 403, etc
//     */
//    private void respondWithHTML(
//        final HttpExchange exchange,
//        final int code,
//        final String content
//    ) {
//        LOGGER.traceEntry(String.format("code:%d content:%s", code, content));
//        System.out.println("-->" + content);   //TODO remove
//        exchange.getResponseHeaders().add("Content-type", "text/html");
//        exchange.getResponseHeaders().add("Cache-Control", "no-store");
//        try {
//            exchange.sendResponseHeaders(code, content.length());
//            OutputStream os = exchange.getResponseBody();
//            os.write(content.getBytes());
//            os.close();
//        } catch (IOException ex) {
//            LOGGER.catching(ex);
//            exchange.close();
//        }
//    }
//
//    /**
//     * Convenient method for sending responses containing JSON
//     * @param exchange The HttpExchance object into which we inject the response
//     * @param code The response code - 200, 201, 403, etc - most responses >= 300 won't necessarily have a JSON formatted body
//     */
//    private void respondWithJSON(
//        final HttpExchange exchange,
//        final int code,
//        final Object object
//    ) {
//        LOGGER.traceEntry(String.format("code:%d object:%s", code, object.toString()));
//        try {
//            ObjectMapper mapper = new ObjectMapper();
//            String content = mapper.writeValueAsString(object);
//            System.out.println("-->" + content);   //TODO remove
//            LOGGER.trace(String.format("  JSON:%s", content));
//            exchange.getResponseHeaders().add("Content-type", "application/json");
//            exchange.getResponseHeaders().add("Cache-Control", "no-store");
//            exchange.sendResponseHeaders(code, content.length());
//            OutputStream os = exchange.getResponseBody();
//            os.write(content.getBytes());
//            os.close();
//        } catch (IOException ex) {
//            LOGGER.catching(ex);
//            exchange.close();
//        }
//    }
//
//    /**
//     * Convenient method for sending responses containing straight text
//     * @param exchange The HttpExchance object into which we inject the response
//     * @param code The response code - 200, 201, 403, etc
//     */
//    private void respondWithText(
//        final HttpExchange exchange,
//        final int code,
//        final String content
//    ) {
//        LOGGER.traceEntry(String.format("code:%d content:%s", code, content));
//        System.out.println("-->" + content);   //TODO remove
//
//        exchange.getResponseHeaders().add("Content-type", "text/plain");
//        byte[] bytes = content.getBytes();
//
//        try {
//            exchange.sendResponseHeaders(code, bytes.length);
//            OutputStream os = exchange.getResponseBody();
//            os.write(bytes);
//            os.close();
//        } catch (IOException ex) {
//            LOGGER.catching(ex);
//            exchange.close();
//        }
//    }
//
//    /**
//     * Validate the credentials in the header of the given exchange object.
//     * Only for POST to /session.
//     * @return true if credentials are valid, else false
//     */
//    private boolean validateCredentials(
//        final HttpExchange exchange
//    ) {
//        Headers headers = exchange.getRequestHeaders();
//        List<String> values = headers.get("Authorization");
//        if ((values != null) && (values.size() == 1)) {
//            String[] split = values.get(0).split(" ");
//            if (split.length == 2) {
//                if (split[0].equalsIgnoreCase("Basic")) {
//                    String unBased = new String(Base64.getDecoder().decode(split[1]));
//                    String[] unBasedSplit = unBased.split(":");
//                    if (unBasedSplit.length == 2) {
//                        String givenUserName = unBasedSplit[0];
//                        String givenClearTextPassword = unBasedSplit[1];
//                        SystemProcessor sp = SystemProcessor.getInstance();
//                        SoftwareConfiguration sc = sp.getSoftwareConfiguration();
//                        if (givenUserName.equalsIgnoreCase(sc._adminCredentials._userName)) {
//                            return sc._adminCredentials.validatePassword(givenClearTextPassword);
//                        }
//                    }
//                }
//            }
//        }
//
//        return false;
//    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Implementations / overrides of abstract base methods
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * For notifying clients that a pending ReadReplyMessage is no long pending,
     * at least insofar as the operating system is concerned.
     */
    public void cancelReadReplyMessage(
        final int messageId
    ) {
        synchronized (_pendingReadReplyMessages) {
            _pendingReadReplyMessages.remove(messageId);
        }

        pokeClients(clientInfo -> clientInfo._updatedReadReplyMessages = true);
    }

    /**
     * For debugging
     */
    @Override
    public void dump(
        final BufferedWriter writer
    ) {
        try {
            writer.write(String.format("RESTSystemConsole %s\n", _name));
            writer.write(String.format("  APIListener commonName=%s portNumber=%d\n",
                                       _listener.getCommonName(),
                                       _listener.getPortNumber()));

            writer.write("  Recent Read-Only Messages:\n");
            synchronized (_recentReadOnlyMessages) {
                for (ReadOnlyMessage msg : _recentReadOnlyMessages) {
                    writer.write(String.format("    '%s''\n", msg._text));
                }
            }

            writer.write("  Pending Read-Reply Messages:\n");
            synchronized (_pendingReadReplyMessages) {
                for (ReadReplyMessage msg : _pendingReadReplyMessages.values()) {
                    writer.write(String.format("    %d:'%s'", msg._messageId, msg._text));
                    writer.write(String.format("       Max reply:%d", msg._maxReplyLength));
                }
            }

            writer.write("  Pending input messages:\n");
            synchronized (_pendingInputMessages) {
                for (Map.Entry<String, InputMessage> entry : _pendingInputMessages.entrySet()) {
                    String clientId = entry.getKey();
                    InputMessage im = entry.getValue();
                    if (im instanceof UnsolicitedInputMessage) {
                        UnsolicitedInputMessage uim = (UnsolicitedInputMessage) im;
                        writer.write(String.format("    clientId=%s:'%s'\n", clientId, uim._text));
                    } else if (im instanceof ReadReplyInputMessage) {
                        ReadReplyInputMessage rrim = (ReadReplyInputMessage) im;
                        writer.write(String.format("    clientId=%s: %d '%s'\n", clientId, rrim._messageId, rrim._text));
                    }
                }
            }

            long now = System.currentTimeMillis();
            synchronized (_clientInfos) {
                for (ClientInfo cinfo : _clientInfos.values()) {
                    synchronized (cinfo) {
                        writer.write(String.format("  Client   %sRemote Address:%s   Last Activity %d msec ago\n",
                                                   cinfo._isMaster ? "MASTER " : "",
                                                   cinfo._remoteAddress.getAddress().getHostAddress(),
                                                   now - cinfo._lastActivity));

                        writer.write("  Pending output messages\n");
                        for (ReadOnlyMessage msg : cinfo._pendingReadOnlyMessages) {
                            writer.write(String.format("    '%s'\n", msg._text));
                        }
                    }
                }
            }
        } catch (IOException ex) {
            LOGGER.catching(ex);
        }
    }

    @Override
    public InputMessage pollInputMessage() {
        InputMessage result = null;
        synchronized (this) {
            Iterator<Map.Entry<String, InputMessage>> iter = _pendingInputMessages.entrySet().iterator();
            if (iter.hasNext()) {
                Map.Entry<String, InputMessage> firstEntry = iter.next();
                String clientId = firstEntry.getKey();
                result = firstEntry.getValue();
                iter.remove();

                ClientInfo cinfo = _clientInfos.get(clientId);
                if (cinfo != null) {
                    cinfo._inputDelivered = true;
                    synchronized (cinfo) {
                        cinfo.notify();
                    }
                }
            }
        }

        return result;
    }

    @Override
    public void postReadOnlyMessage(
        final ReadOnlyMessage message
    ) {
        synchronized (_recentReadOnlyMessages) {
            _recentReadOnlyMessages.add(message);
            while (_recentReadOnlyMessages.size() > MAX_RECENT_READ_ONLY_MESSAGES) {
                _recentReadOnlyMessages.poll();
            }
        }

        pokeClients(clientInfo -> clientInfo._pendingReadOnlyMessages.add(message));
    }

    @Override
    public void postReadReplyMessage(
        final ReadReplyMessage message
    ) {
        synchronized (_pendingReadReplyMessages) {
            _pendingReadReplyMessages.put(message._messageId, message);
        }

        ReadOnlyMessage rom = new ReadOnlyMessage(message._text);
        synchronized (_clientInfos) {
            for (ClientInfo cinfo : _clientInfos.values()) {
                if (cinfo._isMaster) {
                    cinfo._updatedReadReplyMessages = true;
                } else {
                    cinfo._pendingReadOnlyMessages.add(rom);
                }
            }
        }

        pokeClients(null);
    }

    /**
     * Cache the given status message and notify the pending clients that an updated message is available
     */
    @Override
    public void postStatusMessage(
        final StatusMessage message
    ) {
        _latestStatusMessage = message;
        pokeClients(clientInfo -> clientInfo._updatedStatusMessage = true);
    }

    /**
     * Given a set of log entries, propagate all of the ones which do not come from black-listed sources, to any pending clients.
     * If there are none after filtering, don't annoy the clients.
     */
    @Override
    public void postSystemLogEntries(
        final KomodoLoggingAppender.LogEntry[] logEntries
    ) {
        List<KomodoLoggingAppender.LogEntry> logList = new LinkedList<>();
        for (KomodoLoggingAppender.LogEntry logEntry : logEntries) {
            boolean avoid = false;
            for (String s : _logReportingBlackList) {
                if (s.equalsIgnoreCase(logEntry._source)) {
                    avoid = true;
                    break;
                }
            }
            if (!avoid) {
                logList.add(logEntry);
            }
        }

        if (!logList.isEmpty()) {
            synchronized (_recentLogEntries) {
                _recentLogEntries.addAll(logList);
                while (_recentLogEntries.size() > MAX_RECENT_LOG_ENTRIES) {
                    _recentLogEntries.remove(0);
                }
            }
            pokeClients(clientInfo -> clientInfo._pendingLogEntries.addAll(logList));
        }
    }

    /**
     * Reset all of the connected console sessions
     */
    @Override
    public void reset() {
        //  We don't close the established sessions, but we do clear out all messaging and such.
        pokeClients(ClientInfo::clear);
        synchronized(this) {
            _latestStatusMessage = null;
            _pendingInputMessages.clear();
            _pendingReadReplyMessages.clear();
            _recentReadOnlyMessages.clear();
        }
    }

    /**
     * Starts this entity
     */
    @Override
    public boolean start(
    ) {
        try {
            _listener.setup();
            _workerThread = new WorkerThread();
            _workerThread.start();
            return true;
        } catch (Exception ex) {
            LOGGER.catching(ex);
            return false;
        }
    }

    /**
     * Stops this entity
     */
    @Override
    public void stop() {
        _listener.stop();
        _workerThread._terminate = true;
        _workerThread = null;
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Worker thread
    //  ----------------------------------------------------------------------------------------------------------------------------

    /**
     * This async thread/class exists mainly just to prune the established sessions periodically
     */
    private class WorkerThread extends Thread {

        public boolean _terminate = false;

        public void run() {
            while (!_terminate) {
                pruneClients();
                try {
                    Thread.sleep(WORKER_PERIODICITY_MSECS);
                } catch (InterruptedException ex) {
                    LOGGER.catching(ex);
                }
            }
        }
    }
}
