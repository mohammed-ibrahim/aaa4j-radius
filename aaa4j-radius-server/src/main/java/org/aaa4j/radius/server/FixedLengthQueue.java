package org.aaa4j.radius.server;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;

public class FixedLengthQueue {

  String [] queue;
  int index;

  int size;
  public FixedLengthQueue(int size) {
    this.size = size;
    queue = new String[size];
    index = 0;
  }

  public void add(String entry) {
    synchronized (queue) {
      queue[index] = entry;
      index++;

      if (index >= size) {
        index = 0;
      }
    }
  }

  public String getArray() {
    String buffer[] = null;
    synchronized (queue) {
      buffer = Arrays.copyOf(this.queue, size);
    }

    return StringUtils.join(buffer, ", ");
  }

}
