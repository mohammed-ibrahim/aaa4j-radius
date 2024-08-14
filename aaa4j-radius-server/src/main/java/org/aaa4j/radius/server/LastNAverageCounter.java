package org.aaa4j.radius.server;

import java.util.Arrays;
import java.util.Objects;

public class LastNAverageCounter {

  int [] queue;
  int index;

  private Object syncLock = new Object();

  final int size;
  public LastNAverageCounter(int size) {
    this.size = size;
    queue = new int[size];
    index = 0;
  }

  public void add(int i) {
    synchronized (syncLock) {
      queue[index] = i;
      index++;

      if (index >= size) {
        index = 0;
      }
    }
  }


  public int [] getArray() {
    return Arrays.copyOf(this.queue, size);
  }

  public int average() {
    int buffer [] = null;

    synchronized (syncLock) {
      buffer = getArray();
    }

    int total = 0;

    for (int i : buffer) {
      total += i;
    }

    int avg = total/size;

    return avg;
  }
}
