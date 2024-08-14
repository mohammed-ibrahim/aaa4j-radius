package org.aaa4j.radius.server;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FixedSizeStringQueue {

  private String [] queue;

  private int size;

  private int index;

  private Object syncLock = new Object();

  public FixedSizeStringQueue(int size) {
    this.size = size;
    this.queue = new String[size];
  }

  public void add(String entry) {
    synchronized (syncLock) {
      queue[index] = entry;
      index++;

      if (index >= size) {
        index = 0;
      }
    }
  }

  public String presentQueue() {
    List<String> buffer = new ArrayList<>();

    int indexCopy;
    String[] queueCopy;

    synchronized (syncLock) {
      indexCopy = index;
      queueCopy = Arrays.copyOf(this.queue, size);
    }

    if (indexCopy == 0) {
      buffer = Arrays.asList(queueCopy);
    } else {
      for (int i = indexCopy; i<size; i++) {
        buffer.add(queueCopy[i]);
      }

      for (int i = 0; i<indexCopy; i++) {
        buffer.add(queueCopy[i]);
      }
    }

    return StringUtils.join(buffer, ",");
  }

}
