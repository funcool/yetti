// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright © Andrey Antukh <niwi@niwi.nz>

package yetti.util;

import clojure.lang.IFn;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedBinaryMessage;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketChannel;

public class WebSocketListenerWrapper extends AbstractReceiveListener {
  private final IFn _onText;
  private final IFn _onBytes;
  private final IFn _onPing;
  private final IFn _onPong;
  private final IFn _onClose;
  private final IFn _onError;

  public WebSocketListenerWrapper(IFn onText, IFn onBytes, IFn onPing, IFn onPong, IFn onError, IFn onClose) {
    super();
    this._onText = onText;
    this._onBytes = onBytes;
    this._onPing = onPing;
    this._onPong = onPong;
    this._onError = onError;
    this._onClose = onClose;
  }

  public void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) throws java.io.IOException {
    if (this._onText != null) {
      this._onText.invoke(channel, message.getData());
    } else {
      super.onFullTextMessage(channel, message);
    }
  }

  public void onFullBinaryMessage(WebSocketChannel channel, BufferedBinaryMessage message) throws java.io.IOException {
    if (this._onBytes != null) {
      var data = message.getData();
      try {
        this._onBytes.invoke(channel, data.getResource());
      } finally {
        data.free();
      }
    } else {
      super.onFullBinaryMessage(channel, message);
    }
  }

  public void onFullPingMessage(WebSocketChannel channel, BufferedBinaryMessage message) throws java.io.IOException {
    if (this._onPing != null) {
      var data = message.getData();
      try {
        this._onPing.invoke(channel, data.getResource());
      } finally {
        data.free();
      }
    } else {
      super.onFullPingMessage(channel, message);
    }
  }

  public void onFullPongMessage(WebSocketChannel channel, BufferedBinaryMessage message) throws java.io.IOException {
    if (this._onPong != null) {
      var data = message.getData();
      try {
        this._onPong.invoke(channel, data.getResource());
      } finally {
        data.free();
      }
    } else {
      super.onFullPongMessage(channel, message);
    }
  }

  public void onCloseMessage(CloseMessage message, WebSocketChannel channel) {
    if (this._onClose != null) {
      this._onClose.invoke(channel, message.getCode(), message.getReason());
    } else {
      super.onCloseMessage(message, channel);
    }
  }

  public void onError(WebSocketChannel channel, Throwable cause) {
    if (this._onError != null) {
      this._onError.invoke(channel, cause);
    } else {
      super.onError(channel, cause);
    }
  }
}
