package org.aaa4j.radius.core.config;

import org.aaa4j.radius.core.config.DiskFileManager;
import org.apache.commons.io.FileUtils;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.testng.Assert.*;

public class DiskFileManagerTest {

  private String filename;

  private DiskFileManager diskFileManager;

  @BeforeClass
  public void setupClass() {
    try {
      File tempFile = File.createTempFile("at-", "-bt");
      filename = tempFile.toPath().toString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    writeToFile(UUID.randomUUID().toString());
    diskFileManager = new DiskFileManager(filename);
  }


  @AfterClass
  public void cleanup() {
    try {
      new File(filename).delete();
    } catch (Exception e) {
    }
  }
  private void writeToFile(String content) {
    try {
      FileUtils.writeStringToFile(new File(this.filename), content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void canGetFileContents() {
    String content = "a";
    writeToFile(content);
    String actualContent = diskFileManager.getNewContent();
    assertEquals(actualContent, content);
  }

  @Test
  public void canDetectFileChanged() {
    boolean beforeContentChanged = diskFileManager.contentsChanged();
    writeToFile(UUID.randomUUID().toString());
    boolean afterContentChanged = diskFileManager.contentsChanged();
    boolean subsequentAfterChanged = diskFileManager.contentsChanged();

    assertFalse(beforeContentChanged);
    assertTrue(afterContentChanged);
    assertFalse(subsequentAfterChanged);
  }
}