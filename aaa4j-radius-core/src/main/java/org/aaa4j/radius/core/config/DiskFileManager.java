package org.aaa4j.radius.core.config;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DiskFileManager implements IDiskFileManager {
  private static final Logger log = LoggerFactory.getLogger(DiskFileManager.class);

  private String filename;

  private String md5;

  public DiskFileManager(String filename) {
    this.filename = filename;
    this.md5 = getMd5HexOfConfigFile();
  }

  private String getMd5HexOfConfigFile() {
    try (InputStream is = Files.newInputStream(Paths.get(this.filename))) {
      String md5 = DigestUtils.md5Hex(is);
      return md5;
    } catch (Exception e) {
      log.error("Failed to calculate md5", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean contentsChanged() {
    String newMd5 = getMd5HexOfConfigFile();

     if (StringUtils.equals(newMd5, md5)) {
       return false;
     } else {
       md5 = newMd5;
       return true;
     }
  }

  @Override
  public String getNewContent() {
    try {
      return FileUtils.readFileToString(new File(this.filename), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
