/*
 * Sonar Pitest Plugin
 * Copyright (C) 2009-2018 Vinod Anandan
 * vinod@owasp.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.pitest.scanner;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultFileSystem;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.rule.ActiveRules;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.batch.sensor.measure.Measure;
import org.sonar.api.config.Configuration;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.rules.ActiveRule;
import org.sonar.api.rules.Rule;
import org.sonar.plugins.pitest.PitestConstants;
import org.sonar.plugins.pitest.PitestMetrics;
import org.sonar.plugins.pitest.domain.Mutant;
import org.sonar.plugins.pitest.domain.MutantStatus;
import org.sonar.plugins.pitest.domain.TestMutantBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonar.plugins.pitest.PitestConstants.MODE_KEY;
import static org.sonar.plugins.pitest.PitestConstants.MODE_REUSE_REPORT;
import static org.sonar.plugins.pitest.PitestConstants.MODE_SKIP;
import static org.sonar.plugins.pitest.PitestConstants.REPORT_DIRECTORY_DEF;
import static org.sonar.plugins.pitest.PitestConstants.REPORT_DIRECTORY_KEY;
import static org.sonar.plugins.pitest.PitestConstants.REPOSITORY_KEY;

public class PitestSensorTest {

  private static final String MODULE_BASE_DIR = "src/test/resources/pitest-sensor-tests";
  private static final String JAVA_RELATIVE_PATH = "com/foo/Bar.java";
  private static final String JAVA_CLASS = "com.foo.Bar";
  private static final String KOTLIN_RELATIVE_PATH = "Maze.kt";

  @Test
  public void should_describe_execution_conditions() throws Exception {
    // given
    SensorContextTester context = createTestSensorContext();
    Configuration configuration = mockConfiguration();
    PitestSensor sensor = new PitestSensor(configuration, mockXmlReportParser(), mockRulesProfile(true, false), mockXmlReportFinder(), context.fileSystem());

    SensorDescriptor descriptor = spy(SensorDescriptor.class);

    // when
    sensor.describe(descriptor);

    // then
    verify(descriptor).name(PitestSensor.SENSOR_NAME);
    verify(descriptor).onlyOnLanguages("java");
    verify(descriptor).onlyOnFileType(InputFile.Type.MAIN);
    verify(descriptor).createIssuesForRuleRepository(REPOSITORY_KEY);
  }

  @Test
  public void should_skip_analysis_if_mode_is_skip() throws IOException {
    // given
    Configuration configuration = mock(Configuration.class);
    when(configuration.get(MODE_KEY)).thenReturn(Optional.of(MODE_SKIP));
    SensorContextTester context = createTestSensorContext();
    PitestSensor sensor = new PitestSensor(configuration, mock(XmlReportParser.class), mock(ActiveRules.class), mock(XmlReportFinder.class), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    assertThat(context.allIssues()).isEmpty();
  }

  @Test
  public void should_not_fail_if_no_report_found() throws IOException {
    // given
    Configuration configuration = mock(Configuration.class);
    when(configuration.get(MODE_KEY)).thenReturn(Optional.of(MODE_REUSE_REPORT));
    when(configuration.get(REPORT_DIRECTORY_KEY)).thenReturn(Optional.of("nonexistant-directory"));
    SensorContextTester context = createTestSensorContext();
    PitestSensor sensor = new PitestSensor(configuration, mock(XmlReportParser.class), mock(ActiveRules.class), mock(XmlReportFinder.class), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    assertThat(context.allIssues()).isEmpty();
  }

  @Test
  public void should_create_issue_for_survived_mutant() throws Exception {
    // given
    SensorContextTester context = createTestSensorContext();
    PitestSensor sensor = new PitestSensor(mockConfiguration(), mockXmlReportParser(), mockRulesProfile(true, false), mockXmlReportFinder(), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    assertThat(context.allIssues()).hasSize(2);
    assertThat(context.allIssues()).allMatch(i -> i.ruleKey().rule().equals(PitestConstants.SURVIVED_MUTANT_RULE_KEY));

  }

  @Test
  public void should_not_create_issue_for_survived_mutant_if_present_but_rule_not_active() throws Exception {
    // given
    SensorContextTester context = createTestSensorContext();
    PitestSensor sensor = new PitestSensor(mockConfiguration(), mockXmlReportParser(), mockRulesProfile(false, false), mockXmlReportFinder(), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    assertThat(context.allIssues()).isEmpty();

  }



  @Test
  public void should_create_issue_for_coverage_not_met() throws Exception {
    // given
    SensorContextTester context = createTestSensorContext();
    PitestSensor sensor = new PitestSensor(mockConfiguration(), mockXmlReportParser(), mockRulesProfile(false, true), mockXmlReportFinder(), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    // threshold is 50%
    // com/foo/Bar.java : coverage 60%
    // Maze.kt: : killedPercent 33%
    assertThat(context.allIssues()).hasSize(1);
    assertThat(context.allIssues()).allMatch(i -> i.ruleKey().rule().equals(PitestConstants.INSUFFICIENT_MUTATION_COVERAGE_RULE_KEY));

  }

  @Test
  public void should_create_issue_for_coverage_not_met_high_threshold() throws Exception {
    // given
    SensorContextTester context = createTestSensorContext();
    ActiveRules mockRulesProfile = mockRulesProfile(false, true);
    org.sonar.api.batch.rule.ActiveRule mockCoverageRule = mockRulesProfile.findByInternalKey(REPOSITORY_KEY, PitestConstants.INSUFFICIENT_MUTATION_COVERAGE_RULE_KEY);
    when(mockCoverageRule.param(PitestConstants.COVERAGE_RATIO_PARAM)).thenReturn("70");
    PitestSensor sensor = new PitestSensor(mockConfiguration(), mockXmlReportParser(), mockRulesProfile, mockXmlReportFinder(), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    // threshold is 50%
    // com/foo/Bar.java : coverage 60%
    // Maze.kt: : killedPercent 33%
    assertThat(context.allIssues()).hasSize(2);
    assertThat(context.allIssues()).allMatch(i -> i.ruleKey().rule().equals(PitestConstants.INSUFFICIENT_MUTATION_COVERAGE_RULE_KEY));

  }

  @Test
  public void should_not_create_issue_for_coverage_not_met_if_coverage_below_threshold() throws Exception {
    // given
    SensorContextTester context = createTestSensorContext();
    ActiveRules mockRulesProfile = mockRulesProfile(false, true);
    org.sonar.api.batch.rule.ActiveRule mockCoverageRule = mockRulesProfile.findByInternalKey(REPOSITORY_KEY, PitestConstants.INSUFFICIENT_MUTATION_COVERAGE_RULE_KEY);
    when(mockCoverageRule.param(PitestConstants.COVERAGE_RATIO_PARAM)).thenReturn("10");
    PitestSensor sensor = new PitestSensor(mockConfiguration(), mockXmlReportParser(), mockRulesProfile, mockXmlReportFinder(), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    assertThat(context.allIssues()).isEmpty();
  }

  @Test
  public void should_not_create_issue_for_coverage_not_met_if_rule_not_active() throws Exception {
    // given
    SensorContextTester context = createTestSensorContext();
    PitestSensor sensor = new PitestSensor(mockConfiguration(), mockXmlReportParser(), mockRulesProfile(false, false), mockXmlReportFinder(), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    assertThat(context.allIssues()).isEmpty();

  }

  @Test
  public void should_create_measures_if_rules_are_disabled() throws Exception {
    // given
    SensorContextTester context = createTestSensorContext();
    PitestSensor sensor = new PitestSensor(mockConfiguration(), mockXmlReportParser(), mockRulesProfile(false, false), mockXmlReportFinder(), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    String componentKey = "module.key:com/foo/Bar.java";  
    assertThat(context.measures(componentKey)).hasSize(7);
  }
  
  @Test
  public void should_create_measures_if_survive_mutant_rule_is_active() throws Exception {
    // given
    SensorContextTester context = createTestSensorContext();
    PitestSensor sensor = new PitestSensor(mockConfiguration(), mockXmlReportParser(), mockRulesProfile(true, false), mockXmlReportFinder(), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    String componentKey = "module.key:com/foo/Bar.java";  
    assertThat(context.measures(componentKey)).hasSize(7);
  }
  
  
  @Test
  public void should_create_measures_if_insufficient_coverage_rule_is_active() throws Exception {
    // given
    SensorContextTester context = createTestSensorContext();
    PitestSensor sensor = new PitestSensor(mockConfiguration(), mockXmlReportParser(), mockRulesProfile(false, true), mockXmlReportFinder(), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    String componentKey = "module.key:com/foo/Bar.java";  
    assertThat(context.measures(componentKey)).hasSize(7);
  }  

  @Test
  public void measures_for_mock_java_class_should_be_correct() throws Exception {
    // given
    SensorContextTester context = createTestSensorContext();
    PitestSensor sensor = new PitestSensor(mockConfiguration(), mockXmlReportParser(), mockRulesProfile(false, false), mockXmlReportFinder(), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    String componentKey = "module.key:com/foo/Bar.java";  
    assertThat(context.measures(componentKey)).hasSize(7);
    assertMeasure(context, componentKey, PitestMetrics.MUTATIONS_NOT_COVERED_KEY, 1);
    assertMeasure(context, componentKey, PitestMetrics.MUTATIONS_GENERATED_KEY, 5);
    assertMeasure(context, componentKey, PitestMetrics.MUTATIONS_KILLED_KEY, 3);
    assertMeasure(context, componentKey, PitestMetrics.MUTATIONS_SURVIVED_KEY, 1);
    assertMeasure(context, componentKey, PitestMetrics.MUTATIONS_ERROR_KEY, 0);
    assertMeasure(context, componentKey, PitestMetrics.MUTATIONS_UNKNOWN_KEY, 0);  
  }
  
  @Test
  public void measures_for_mock_kotlin_class_should_be_correct() throws Exception {
    // given
    SensorContextTester context = createTestSensorContext();
    PitestSensor sensor = new PitestSensor(mockConfiguration(), mockXmlReportParser(), mockRulesProfile(false, false), mockXmlReportFinder(), context.fileSystem());

    // when
    sensor.execute(context);

    // then
    String componentKey = "module.key:Maze.kt";  
    assertThat(context.measures(componentKey)).hasSize(7);
    assertMeasure(context, componentKey, PitestMetrics.MUTATIONS_NOT_COVERED_KEY, 1);
    assertMeasure(context, componentKey, PitestMetrics.MUTATIONS_GENERATED_KEY, 3);
    assertMeasure(context, componentKey, PitestMetrics.MUTATIONS_KILLED_KEY, 1);
    assertMeasure(context, componentKey, PitestMetrics.MUTATIONS_SURVIVED_KEY, 1);
    assertMeasure(context, componentKey, PitestMetrics.MUTATIONS_ERROR_KEY, 0);
    assertMeasure(context, componentKey, PitestMetrics.MUTATIONS_UNKNOWN_KEY, 0);  
  }  
  private void assertMeasure(SensorContextTester context, String componentKey, String metricKey, Integer expectedValue) {
    Measure<Serializable> measure = context.measure(componentKey, metricKey);
    Serializable value = measure.value();
      assertThat(value).isEqualTo(expectedValue);
  }
  
  private Configuration mockConfiguration() {
    Configuration configuration = mock(Configuration.class);
    when(configuration.get(MODE_KEY)).thenReturn(Optional.of(MODE_REUSE_REPORT));
    when(configuration.get(REPORT_DIRECTORY_KEY)).thenReturn(Optional.of(REPORT_DIRECTORY_DEF));
    return configuration;
  }

  private XmlReportParser mockXmlReportParser() {
    XmlReportParser xmlReportParser = mock(XmlReportParser.class);
    when(xmlReportParser.parse(any(File.class))).thenReturn(mutantsBackedByFileSystem());
    return xmlReportParser;
  }

  private XmlReportFinder mockXmlReportFinder() {
    XmlReportFinder xmlReportFinder = mock(XmlReportFinder.class);
    when(xmlReportFinder.findReport(any(File.class))).thenReturn(new File("fake-report.xml"));
    return xmlReportFinder;
  }

  private ActiveRules mockRulesProfile(boolean survivedMutantRuleActive, boolean coverageRuleActive) {
    ActiveRules qualityProfile = mock(ActiveRules.class);

    if (survivedMutantRuleActive) {
      org.sonar.api.batch.rule.ActiveRule survivedMutantRule = mock(org.sonar.api.batch.rule.ActiveRule.class);
      //when(survivedMutantRule.ruleKey()).thenReturn(Rule.create());
      when(qualityProfile.findByInternalKey(PitestConstants.REPOSITORY_KEY, PitestConstants.SURVIVED_MUTANT_RULE_KEY)).thenReturn(survivedMutantRule);
    }
    if (coverageRuleActive) {
      org.sonar.api.batch.rule.ActiveRule coverageRule = mock(org.sonar.api.batch.rule.ActiveRule.class);
      when(coverageRule.param(PitestConstants.COVERAGE_RATIO_PARAM)).thenReturn("50");
   //   when(coverageRule.getRule()).thenReturn(Rule.create());
      when(qualityProfile.findByInternalKey(PitestConstants.REPOSITORY_KEY, PitestConstants.INSUFFICIENT_MUTATION_COVERAGE_RULE_KEY)).thenReturn(coverageRule);

    }
    return qualityProfile;
  }

  private SensorContextTester createTestSensorContext() throws IOException {

    SensorContextTester context = SensorContextTester.create(new File(MODULE_BASE_DIR));
    DefaultFileSystem fs = context.fileSystem();

    File javaFile = new File(fs.baseDir(), JAVA_RELATIVE_PATH);
    DefaultInputFile javaInputFile = new TestInputFileBuilder("module.key", JAVA_RELATIVE_PATH).setLanguage("java").setModuleBaseDir(fs.baseDirPath())
      .setType(InputFile.Type.MAIN)
      .setLines(1000)
     // .setOriginalLineOffsets(new int[] {0, 2, 10, 42, 1000})
      .setLastValidOffset(1)
      .initMetadata(new String(Files.readAllBytes(javaFile.toPath()), StandardCharsets.UTF_8))
      .setCharset(StandardCharsets.UTF_8)
      .build();
    fs.add(javaInputFile);

    File kotlinFile = new File(fs.baseDir(), KOTLIN_RELATIVE_PATH);
    DefaultInputFile kotlinInputFile = new TestInputFileBuilder("module.key", KOTLIN_RELATIVE_PATH).setModuleBaseDir(fs.baseDirPath())
      .setType(InputFile.Type.MAIN)
      .setLines(1000)
   //   .setOriginalLineOffsets(new int[] {0, 2, 10, 42, 1000})
      .setLastValidOffset(1)
      .initMetadata(new String(Files.readAllBytes(kotlinFile.toPath()), StandardCharsets.UTF_8))
      .setCharset(StandardCharsets.UTF_8)
      .build();
    fs.add(kotlinInputFile);

    return context;
  }

  private List<Mutant> mutantsBackedByFileSystem() {

    List<Mutant> mutants = new ArrayList<>();
    // 60% coverage on Java class com.foo.Bar
    /*
     * MUTATIONS_NOT_COVERED = 1
     * MUTATIONS_GENERATED = 5
     * MUTATIONS_KILLED = 3
     * MUTATIONS_SURVIVED = 1
     * MUTATIONS_ERROR = 0 
     * MUTATIONS_UNKNOWN = 0 
     */
    mutants.add(new TestMutantBuilder().detected(true).mutantStatus(MutantStatus.KILLED).className(JAVA_CLASS).sourceFile(JAVA_RELATIVE_PATH).build());
    mutants.add(new TestMutantBuilder().detected(true).mutantStatus(MutantStatus.KILLED).className(JAVA_CLASS).sourceFile(JAVA_RELATIVE_PATH).build());
    mutants.add(new TestMutantBuilder().detected(true).mutantStatus(MutantStatus.KILLED).className(JAVA_CLASS).sourceFile(JAVA_RELATIVE_PATH).build());
    mutants.add(new TestMutantBuilder().detected(false).mutantStatus(MutantStatus.SURVIVED).className(JAVA_CLASS).sourceFile(JAVA_RELATIVE_PATH).build());
    mutants.add(new TestMutantBuilder().detected(false).mutantStatus(MutantStatus.NO_COVERAGE).className(JAVA_CLASS).sourceFile(JAVA_RELATIVE_PATH).build());

    // 33% coverage on Kotlin class Maze.kt
    /*
     * MUTATIONS_NOT_COVERED = 1
     * MUTATIONS_GENERATED = 3
     * MUTATIONS_KILLED = 1
     * MUTATIONS_SURVIVED = 1
     * MUTATIONS_ERROR = 0 
     * MUTATIONS_UNKNOWN = 0 
     */    
    mutants.add(new TestMutantBuilder().detected(false).mutantStatus(MutantStatus.SURVIVED).sourceFile(KOTLIN_RELATIVE_PATH).build());
    mutants.add(new TestMutantBuilder().detected(true).mutantStatus(MutantStatus.KILLED).sourceFile(KOTLIN_RELATIVE_PATH).build());
    mutants.add(new TestMutantBuilder().detected(false).mutantStatus(MutantStatus.NO_COVERAGE).sourceFile(KOTLIN_RELATIVE_PATH).build());

    return mutants;
  }



  
}
