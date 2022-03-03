// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright © Andrey Antukh <niwi@niwi.nz>

package yetti.util;

import java.nio.ByteBuffer;

public class ByteBufferHelpers {
  public static ByteBuffer emptyByteBuffer = ByteBuffer.allocate(0);

  public static ByteBuffer copyMany(final ByteBuffer[] buffers) {
    int size = 0;
    for (int i=0; i<buffers.length; i++) {
      size += buffers[0].remaining();
    }

    if (size == 0) {
      return emptyByteBuffer;
    }

    var result = ByteBuffer.allocate(size);
    for (ByteBuffer item : buffers) {
      var copyItem = item.asReadOnlyBuffer();
      copyItem.rewind();
      result.put(copyItem);
    }

    result.rewind();
    return result;
  }
}
