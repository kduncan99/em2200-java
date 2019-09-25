/*
 * Copyright (c) 2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.kconsole;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kadware.komodo.baselib.SecureClient;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("Duplicates")
class ConnectDialog {

    private final Console _console;

    private TextField _hostNameField = null;
    private TextField _portNumberField = null;
    private TextField _userNameField = null;
    private TextField _passwordField = null;

    /**
     * Handles the Cancel button
     */
    private static class CancelPressed implements EventHandler<ActionEvent> {

        public void handle(
            final ActionEvent event
        ) {
            Platform.exit();
        }
    }

    /**
     * Handles the Connect button
     */
    private class ConnectPressed implements EventHandler<ActionEvent> {

        /**
         * Open a connection as directed, and see whether the target is responsive, and an actual SystemProcessor interface
         * If everything looks okay, transition to the main window.
         */
        public void handle(
            final ActionEvent event
        ) {
            try {
                //  validate input fields....ish
                if (_hostNameField.getText().isEmpty()) {
                    new Alert(Alert.AlertType.ERROR, "Host name must be specified").showAndWait();
                    return;
                }

                if (_portNumberField.getText().isEmpty()) {
                    new Alert(Alert.AlertType.ERROR, "Port number must be specified").showAndWait();
                    return;
                }

                int portNumber = Integer.parseInt(_portNumberField.getText());
                if ((portNumber <= 0) || (portNumber > 65535)) {
                    throw new NumberFormatException();
                }

                if (_userNameField.getText().isEmpty()) {
                    new Alert(Alert.AlertType.ERROR, "User name must be specified").showAndWait();
                    return;
                }

                //  attempt a connection
                SecureClient client = new SecureClient(_hostNameField.getText(), portNumber);
                String composition = _userNameField.getText() + ":" + _passwordField.getText();
                String hash = Base64.getEncoder().encodeToString(composition.getBytes());
                client.addProperty("Authorization", "Basic " + hash);

                SecureClient.SendResult result = client.sendGet("/");
                if (result._responseCode == 401) {
                    new Alert(Alert.AlertType.ERROR, "Credentials invalid").showAndWait();
                    return;
                } else if (result._responseCode > 299) {
                    new Alert(Alert.AlertType.ERROR,
                              String.format("Code:%d (%s)", result._responseCode, result._responseMessage)).showAndWait();
                    return;
                }

                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> data = mapper.readValue(result._responseStream, new TypeReference<Map<String, Object>>(){ });
                _console._systemIdent = "unknown";
                Object obj = data.get("SystemIdentifier");
                if (obj instanceof String) { _console._systemIdent = (String) obj; }

                _console._systemVersion = "unknown";
                obj = data.get("VersionString");
                if (obj instanceof String) { _console._systemVersion = (String) obj; }

                //  good to go - spin up the main window
                _console._secureClient = client;
                _console._primaryStage.setScene(_console._mainWindow.createScene());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Invalid port number").showAndWait();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
            }
        }
    }

    ConnectDialog(Console console) { _console = console; }

    Scene createScene() {
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(25, 25, 25, 25));

        Text sceneTitle = new Text("Enter Host Connection Info");
        sceneTitle.setFont(Font.font("Tahoma", FontWeight.NORMAL, 20));
        grid.add(sceneTitle, 0, 0, 2, 1);

        Label hostName = new Label("Host:");
        grid.add(hostName, 0, 1);

        _hostNameField = new TextField();
        _hostNameField.setText("localhost");
        grid.add(_hostNameField, 1, 1);

        Label portNumber = new Label("Port:");
        grid.add(portNumber, 0, 2);

        _portNumberField = new TextField();
        _portNumberField.setText("2200");
        grid.add(_portNumberField, 1, 2);

        Label userName = new Label("User Name:");
        grid.add(userName, 0, 3);

        _userNameField = new TextField();
        grid.add(_userNameField, 1, 3);

        Label password = new Label("Password:");
        grid.add(password, 0, 4);

        _passwordField = new PasswordField();
        grid.add(_passwordField, 1, 4);

        Button cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(new CancelPressed());

        Button connectButton = new Button("Connect");
        connectButton.setDefaultButton(true);
        connectButton.setOnAction(new ConnectPressed());

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.BOTTOM_RIGHT);
        buttonBox.getChildren().add(cancelButton);
        buttonBox.getChildren().add(connectButton);

        grid.add(buttonBox, 1, 5);

        return new Scene(grid, 300, 275);
    }
}
