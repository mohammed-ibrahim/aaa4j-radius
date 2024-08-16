package org.aaa4j.radius.core.config;

import org.aaa4j.radius.core.config.ConfigContainer;
import org.aaa4j.radius.core.config.IDiskFileManager;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNull;

public class ConfigContainerTest {


  private ConfigContainer configContainer;

  @Mock
  private IDiskFileManager iDiskFileManager;

  @BeforeMethod
  public void setupMethod() {
    MockitoAnnotations.openMocks(this);
  }

  @AfterMethod
  public void resetMock() {
    Mockito.reset(iDiskFileManager);
  }

  @Test
  public void canGetProperty() {
    String fileContents = "a=b";
    when(iDiskFileManager.getNewContent()).thenReturn(fileContents, fileContents, fileContents);
    configContainer = new ConfigContainer(iDiskFileManager);

    String actualValue = configContainer.getProperty("a");
    assertEquals(actualValue, "b", "Didn't match expected value");
  }

  @Test
  public void canDetectChangeAndReflect() {
    String fileContents = "a=b";
    when(iDiskFileManager.getNewContent()).thenReturn(fileContents);
    configContainer = new ConfigContainer(iDiskFileManager);

    String actualValue = configContainer.getProperty("a");
    assertEquals(actualValue, "b", "Didn't match expected value");

    fileContents = "c=d";
    when(iDiskFileManager.contentsChanged()).thenReturn(true);
    when(iDiskFileManager.getNewContent()).thenReturn(fileContents);
    configContainer.refresh();

    actualValue = configContainer.getProperty("c");
    assertEquals(actualValue, "d");

    actualValue = configContainer.getProperty("a");
    assertNull(actualValue);
  }

  @Test
  public void canGetIntegerValue() {
    String fileContents = "a=1\nb=hello";
    when(iDiskFileManager.getNewContent()).thenReturn(fileContents);
    configContainer = new ConfigContainer(iDiskFileManager);

    Optional<Integer> actualOptional = configContainer.getPropertyAsInteger("a");
    assertEquals(actualOptional.get(), Integer.valueOf(1));

    actualOptional = configContainer.getPropertyAsInteger("b");
    assertFalse(actualOptional.isPresent());
  }
    
}