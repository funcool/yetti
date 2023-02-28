// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright © Andrey Antukh <niwi@niwi.nz>

package yetti.util;

import java.io.OutputStream;
import java.io.IOException;

public class BufferedOutputStream extends java.io.BufferedOutputStream {
  public BufferedOutputStream(OutputStream out) {
    super(out);
  }

  public BufferedOutputStream(OutputStream out, int size) {
    super(out, size);
  }

  @Override
  public void flush() {
    // DO NOTHING
  }

  @Override
  public void close() throws IOException {
    super.flush();
    super.close();
  }
}


