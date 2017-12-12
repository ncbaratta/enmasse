package io.enmasse.systemtest.web;


import com.paulhammant.ngwebdriver.NgWebDriver;
import io.enmasse.systemtest.Environment;
import io.enmasse.systemtest.Logging;
import io.enmasse.systemtest.OpenShift;
import org.apache.commons.io.FileUtils;
import org.junit.runner.Description;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;

public class SeleniumProvider {

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss:SSSS");
    public WebDriver driver;
    public NgWebDriver angularDriver;
    public WebDriverWait driverWait;
    private Map<Date, File> browserScreenshots = new HashMap<>();
    private String webconsoleFolder = "selenium_tests";
    private Environment environment;
    private OpenShift openShift;


    protected void onFailed(Throwable e, Description description) {
        try {
            Path path = Paths.get(
                    environment.testLogDir(),
                    webconsoleFolder,
                    description.getClassName(),
                    description.getMethodName());
            Files.createDirectories(path);
            for (Date key : browserScreenshots.keySet()) {
                FileUtils.copyFile(browserScreenshots.get(key), new File(Paths.get(path.toString(),
                        String.format("%s_%s.png", description.getDisplayName(), dateFormat.format(key))).toString()));
            }
        } catch (Exception ex) {
            Logging.log.warn("Cannot save screenshots: " + ex.getMessage());
        }
    }


    public void setupDriver(Environment environment, OpenShift openShift, WebDriver driver) throws Exception {
        this.environment = environment;
        this.openShift = openShift;
        this.driver = driver;
        angularDriver = new NgWebDriver((JavascriptExecutor) driver);
        driverWait = new WebDriverWait(driver, 10);
        browserScreenshots.clear();
    }


    public void tearDownDrivers() throws Exception {
        takeScreenShot();
        Thread.sleep(3000);
        try {
            driver.close();
        } catch (Exception ex) {
            Logging.log.warn("Raise exception in close: " + ex.getMessage());
        }

        try {
            driver.quit();
        } catch (Exception ex) {
            Logging.log.warn("Raise warning on quit: " + ex.getMessage());
        }
        Logging.log.info("Driver is closed");
        driver = null;
        angularDriver = null;
        driverWait = null;
    }

    protected WebDriver getDriver() {
        return this.driver;
    }

    protected NgWebDriver getAngularDriver() {
        return this.angularDriver;
    }

    protected WebDriverWait getDriverWait() {
        return driverWait;
    }


    protected void takeScreenShot() {
        try {
            browserScreenshots.put(new Date(), ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE));
        } catch (Exception ex) {
            Logging.log.warn("Cannot take screenshot: " + ex.getMessage());
        }
    }


    protected void clickOnItem(WebElement element) throws Exception {
        clickOnItem(element, null);
    }

    protected void executeJavaScript(String script) throws Exception {
        executeJavaScript(script, null);
    }

    protected void executeJavaScript(String script, String textToLog) throws Exception {
        takeScreenShot();
        assertNotNull(script);
        Logging.log.info("Execute script: " + (textToLog == null ? script : textToLog));
        ((JavascriptExecutor) driver).executeScript(script);
        angularDriver.waitForAngularRequestsToFinish();
        takeScreenShot();
    }

    protected void clickOnItem(WebElement element, String textToLog) throws Exception {
        takeScreenShot();
        assertNotNull(element);
        Logging.log.info("Click on button: " + (textToLog == null ? element.getText() : textToLog));
        element.click();
        angularDriver.waitForAngularRequestsToFinish();
        takeScreenShot();
    }


    protected void fillInputItem(WebElement element, String text) throws Exception {
        takeScreenShot();
        assertNotNull(element);
        element.sendKeys(text);
        angularDriver.waitForAngularRequestsToFinish();
        Logging.log.info("Filled input with text: " + text);
        takeScreenShot();
    }

    protected void pressEnter(WebElement element) throws Exception {
        takeScreenShot();
        assertNotNull(element);
        element.sendKeys(Keys.RETURN);
        angularDriver.waitForAngularRequestsToFinish();
        Logging.log.info("Enter pressed");
        takeScreenShot();
    }
}
