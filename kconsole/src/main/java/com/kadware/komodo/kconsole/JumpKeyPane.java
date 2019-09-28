/*
 * Copyright (c) 2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.kconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kadware.komodo.commlib.SecureClient;
import com.kadware.komodo.commlib.SystemProcessorJumpKeys;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
import java.util.HashMap;

@SuppressWarnings("Duplicates")
class JumpKeyPane extends GridPane {

    private static class ButtonHandler implements EventHandler<ActionEvent> {

        private final ConsoleInfo _consoleInfo;
        private final int _jumpKey;
        private final ToggleButton _toggleButton;

        private ButtonHandler(
            final ConsoleInfo consoleInfo,
            final ToggleButton toggleButton,
            final int jumpKey
        ) {
            _consoleInfo = consoleInfo;
            _jumpKey = jumpKey;
            _toggleButton = toggleButton;
        }

        @Override
        public void handle(ActionEvent event) {
            try {
                SystemProcessorJumpKeys spjk = new SystemProcessorJumpKeys();
                spjk._componentValues = new HashMap<>();
                spjk._componentValues.put(String.valueOf(_jumpKey), _toggleButton.isSelected());
                ObjectMapper mapper = new ObjectMapper();
                String content = mapper.writeValueAsString(spjk);
                SecureClient.SendResult sendResult = _consoleInfo._secureClient.sendPut("/jumpkeys", content.getBytes());
            } catch (Exception ex) {
                //  do nothing
            }
        }
    }

    private final ConsoleInfo _consoleInfo;
    private final ToggleButton[] _buttons = new ToggleButton[36];

    private JumpKeyPane(ConsoleInfo consoleInfo) { _consoleInfo = consoleInfo; }

    static JumpKeyPane create(
        final ConsoleInfo consoleInfo
    ) {
        //TODO mouse-overs to describe the usage of the jump key?
        JumpKeyPane pane = new JumpKeyPane(consoleInfo);
        for (int row = 0; row < 9; ++row) {
            for (int col = 0; col < 4; ++col) {
                int jx = row + (col * 9);
                int jumpKey = jx + 1;
                ToggleButton button = new ToggleButton(String.format("JK%d", jx + 1));
                button.setOnAction(new ButtonHandler(consoleInfo, button, jumpKey));
                pane.add(button, col, row);
                pane._buttons[jx] = button;
            }
        }

        return pane;
    }

    /**
     * jump keys have been updated - update the pane
     */
    void update(
        final long value
    ) {
        long mask = 0_400000_000000L;
        for (int jx = 0; jx < 36; ++jx) {
            _buttons[jx].setSelected((value & mask) != 0);
            mask >>= 1;
        }
    }
}
