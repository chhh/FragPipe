package com.dmtavt.fragpipe.cmd;

import com.dmtavt.fragpipe.Fragpipe;
import com.dmtavt.fragpipe.api.InputLcmsFile;
import com.dmtavt.fragpipe.api.PyInfo;
import com.dmtavt.fragpipe.exceptions.NoStickyException;
import com.dmtavt.fragpipe.messages.NoteConfigPython;
import com.dmtavt.fragpipe.tools.comet.CometCleavageType;
import com.dmtavt.fragpipe.tools.dbsplit.DbSplit2;
import com.dmtavt.fragpipe.tools.enums.CleavageType;
import com.dmtavt.fragpipe.tools.enums.MassTolUnits;
import com.dmtavt.fragpipe.tools.enums.PrecursorMassTolUnits;
import com.dmtavt.fragpipe.tools.fragger.MsfraggerParams;
import com.github.chhh.utils.OsUtils;
import com.github.chhh.utils.StringUtils;
import com.github.chhh.utils.UsageTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import static com.github.chhh.utils.PathUtils.testFilePath;

public class CmdComet extends CmdBase {
    private static final Logger log = LoggerFactory.getLogger(CmdComet.class);
    public static final String NAME = "Comet";

    private static volatile FileFilter ff = null;
    private static volatile Predicate<File> supportedFilePredicate = null;
    private static final Path PATH_NONE = Paths.get("");
    private static volatile Path pathThermo = PATH_NONE;
    private static volatile Path pathBruker = PATH_NONE;
    private MsfraggerParams paramsDda;
    private MsfraggerParams paramsDia;
    private MsfraggerParams paramsGpfDia;

    public CmdComet(boolean isRun, Path workDir) {
        super(isRun, workDir);
    }

    @Override
    public String getCmdName() {
        return NAME;
    }

    private String getPepxmlFn(InputLcmsFile f, String ext, int rank) {
        if (rank > 0) {
            return StringUtils.upToLastDot(f.getPath().getFileName().toString()) + "_rank" + rank + "." + ext;
        } else {
            return StringUtils.upToLastDot(f.getPath().getFileName().toString()) + "." + ext;
        }
    }

    public Map<InputLcmsFile, List<Path>> outputs(List<InputLcmsFile> inputs, String ext, Path workDir) {
        Map<InputLcmsFile, List<Path>> m = new HashMap<>();
        for (InputLcmsFile f : inputs) {
            if (!f.getDataType().contentEquals("DDA")) {
                throw new IllegalArgumentException("Only DDA inputs are supported by Comet");
            }
            if (!f.getDataType().contentEquals("DDA") && !ext.contentEquals("tsv") && !ext.contentEquals("pin")) {
                int maxRank = 5;
                if (f.getDataType().contentEquals("DIA") || f.getDataType().contentEquals("DIA-Lib")) {
                    if (paramsDia == null) {
                        maxRank = 5; // The report_topN_rank is 5 by default for DIA data.
                    } else {
                        maxRank = paramsDia.getOutputReportTopN();
                    }
                } else if (f.getDataType().contentEquals("GPF-DIA")) {
                    if (paramsGpfDia == null) {
                        maxRank = 3; // The report_topN_rank is 3 by default for GPF-DIA data.
                    } else {
                        maxRank = paramsGpfDia.getOutputReportTopN();
                    }
                }

                for (int rank = 1; rank <= maxRank; ++rank) {
                    String pepxmlFn = getPepxmlFn(f, ext, rank);
                    List<Path> t = m.get(f);
                    if (t == null) {
                        t = new ArrayList<>();
                        t.add(f.outputDir(workDir).resolve(pepxmlFn));
                        m.put(f, t);
                    } else {
                        t.add(f.outputDir(workDir).resolve(pepxmlFn));
                    }
                }
            } else {
                String pepxmlFn = getPepxmlFn(f, ext, 0);
                List<Path> tempList = new ArrayList<>(1);
                tempList.add(f.outputDir(workDir).resolve(pepxmlFn));
                m.put(f, tempList);
            }
        }
        return m;
    }

    private static void showError(boolean isHeadless, String msg, Component comp) {
        if (isHeadless) {
            log.error(msg);
        } else {
            JOptionPane.showMessageDialog(comp, msg + "\n", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void showError(String msg, Component comp) {
        if (Fragpipe.headless) {
            log.error(msg);
        } else {
            JOptionPane.showMessageDialog(comp, msg + "\n", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    public boolean configure(Component comp, boolean isDryRun, Path jarFragpipe, UsageTrigger binComet, String pathFasta, String cometParamsPath, int numSlices, int ramGb, List<InputLcmsFile> lcmsFiles, final String decoyTag, boolean hasDda, boolean hasDia, boolean hasGpfDia, boolean hasDiaLib, boolean isRunDiaU) {

        initPreConfig();
        if (StringUtils.isNullOrWhitespace(binComet.getBin())) {
            showError(Fragpipe.headless, "Binary for running Comet can not be an empty string.", comp);
            return false;
        }
        if (testFilePath(binComet.getBin(), "") == null) {
            showError(Fragpipe.headless,"Binary for running Fragger not found or could not be run.\nNeither on PATH, nor in the working directory", comp);
            return false;
        }

        boolean isOpenFormats = lcmsFiles.stream().map(lcms -> lcms.getPath().toString().toLowerCase(Locale.ROOT))
                .allMatch(f -> f.endsWith(".mzml") || f.endsWith(".mzxml"));
        if (!isOpenFormats) {
            showError(Fragpipe.headless, "Comet only supports mzml and mzxml", comp);
            return false;
        }

        // Fasta file
        if (pathFasta == null) {
            showError(Fragpipe.headless, "Fasta file path (Fragger) can't be empty", comp);
            return false;
        }

        if ((hasDia || hasGpfDia || hasDiaLib) && !isRunDiaU) {
            showError(Fragpipe.headless, "Comet can't process DIA data natively, enable DIA-Umpire", comp);
            return false;
        }

        // Search parameter file
        if (!Paths.get(cometParamsPath).toFile().exists()) {
            showError(Fragpipe.headless, "Comet can't process DIA data natively, enable DIA-Umpire", comp);
        }

        //params.setDatabaseName(pathFasta); // we will pass it as a parameter instead
//        params.setDecoyPrefix(decoyTag);
//        Path savedDdaParamsPath = (hasDia || hasGpfDia || hasDiaLib) ? wd.resolve("fragger_dda.params") : wd.resolve("fragger.params");
//        Path savedDiaParamsPath = wd.resolve("fragger_dia.params");
//        Path savedGpfDiaParamsPath = wd.resolve("fragger_gpfdia.params");
//
//        paramsDda = new MsfraggerParams(params);
//        paramsDia = new MsfraggerParams(params);
//        paramsGpfDia = new MsfraggerParams(params);
//
//        paramsDda.setDataType(0);
//        adjustDiaParams(params, paramsDia, "DIA");
//        adjustDiaParams(params, paramsGpfDia, "GPF-DIA");

        // 32k symbols splitting for regular command.
        // But for slicing it's all up to the python script.
        // final int commandLenLimit = isSlicing ? Integer.MAX_VALUE : 32000;
        final int commandLenLimit = 32000; // Make is a little bit smaller than 1 << 15 to make sure that it won't crash.

    /* disable deletion of temp dir when error occurs
    if (isSlicing) {
      // schedule to always try to delete the temp dir when FragPipe finishes execution
      final String tempDirName = "split_peptide_index_tempdir";
      Path toDelete = wd.resolve(tempDirName).toAbsolutePath().normalize();
      toDelete.toFile().deleteOnExit();
      ProcessManager.addFilesToDelete(Collections.singleton(toDelete));
      try {
        if (Files.exists(toDelete)) {
          FileUtils.deleteDirectory(toDelete.toFile());
        }
      } catch (IOException e) {
        log.error("Could not delete leftover temporary directory from DB Splitting", e);
      }
    }
    */

        StringBuilder sb = new StringBuilder();

        Map<InputLcmsFile, List<Path>> mapLcmsToPepxml = outputs(lcmsFiles, "pepXML", wd);
//        Map<InputLcmsFile, List<Path>> mapLcmsToTsv = outputs(lcmsFiles, "tsv", wd);
//        Map<InputLcmsFile, List<Path>> mapLcmsToPin = outputs(lcmsFiles, "pin", wd);

        final List<String> javaCmd = Arrays.asList(
                Fragpipe.getBinJava(), "-jar", "-Dfile.encoding=UTF-8", "-Xmx" + ramGb + "G");



        Map<String, List<InputLcmsFile>> t = new TreeMap<>();

        for (InputLcmsFile inputLcmsFile : lcmsFiles) {
            if (inputLcmsFile.getDataType().contentEquals("DDA")) {
                List<InputLcmsFile> tt = t.get("DDA");
                if (tt == null) {
                    tt = new ArrayList<>();
                    tt.add(inputLcmsFile);
                    t.put("DDA", tt);
                } else {
                    tt.add(inputLcmsFile);
                }
            } else if (inputLcmsFile.getDataType().contentEquals("DIA") || inputLcmsFile.getDataType().contentEquals("DIA-Lib")) { // searching DIA and DIA-Lib together
                List<InputLcmsFile> tt = t.get("DIA");
                if (tt == null) {
                    tt = new ArrayList<>();
                    tt.add(inputLcmsFile);
                    t.put("DIA", tt);
                } else {
                    tt.add(inputLcmsFile);
                }
            } else if (inputLcmsFile.getDataType().contentEquals("GPF-DIA")) {
                List<InputLcmsFile> tt = t.get("GPF-DIA");
                if (tt == null) {
                    tt = new ArrayList<>();
                    tt.add(inputLcmsFile);
                    t.put("GPF-DIA", tt);
                } else {
                    tt.add(inputLcmsFile);
                }
            }
        }

        for (Map.Entry<String, List<InputLcmsFile>> e : t.entrySet()) {
            int fileIndex = 0;
            while (fileIndex < e.getValue().size()) {
                List<String> cmd = new ArrayList<>();
                cmd.add(binComet.useBin());

                // Execution order after sorting: DDA, DIA and DIA-Lib, GPF-DIA. MSFragger would stop if there were wide isolation windows in DDA mode, which makes it better to let DDA be executed first.
                if (e.getKey().contentEquals("DDA")) {
                    cmd.add(savedDdaParamsPath.toString());
                } else if (e.getKey().contentEquals("DIA")) {
                    cmd.add(savedDiaParamsPath.toString());
                } else if (e.getKey().contentEquals("GPF-DIA")) {
                    cmd.add(savedGpfDiaParamsPath.toString());
                }

                // check if the command length is ok so far
                sb.append(String.join(" ", cmd));
                if (sb.length() > commandLenLimit) {
                    if (Fragpipe.headless) {
                        log.error("MSFragger command line length too large even for a single file.");
                    } else {
                        JOptionPane.showMessageDialog(comp, "MSFragger command line length too large even for a single file.", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                    return false;
                }

                List<InputLcmsFile> addedLcmsFiles = new ArrayList<>();
                while (fileIndex < e.getValue().size()) {
                    InputLcmsFile f = e.getValue().get(fileIndex);
                    // if adding this file to the command line will make the command length
                    // longer than the allowed maximum, stop adding files
                    if (sb.length() + f.getPath().toString().length() > commandLenLimit) {
                        break;
                    }
                    sb.append(f.getPath().toString()).append(" ");
                    cmd.add(f.getPath().toString());
                    addedLcmsFiles.add(f);
                    fileIndex++;
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);

                if (isSlicing) {
                    PyInfo.modifyEnvironmentVariablesForPythonSubprocesses(pb);
                    pb.environment().put("PYTHONIOENCODING", "utf-8");
                    pb.environment().put("PYTHONUNBUFFERED", "true");
                }

                pb.directory(wd.toFile());

                pbis.add(PbiBuilder.from(pb));
                sb.setLength(0);

                // move the pepxml files if the output directory is not the same as where
                // the lcms files were
                for (InputLcmsFile f : addedLcmsFiles) {
                    if (fraggerOutputType.valueInParamsFile().contains("pepXML")) {
                        List<Path> pepxmlWhereItShouldBeList = mapLcmsToPepxml.get(f);
                        if (pepxmlWhereItShouldBeList == null || pepxmlWhereItShouldBeList.isEmpty())
                            throw new IllegalStateException("LCMS file mapped to no pepxml file");
                        for (Path pepxmlWhereItShouldBe : pepxmlWhereItShouldBeList) {
                            String pepxmlFn = pepxmlWhereItShouldBe.getFileName().toString();
                            Path pepxmlAsCreatedByFragger = f.getPath().getParent().resolve(pepxmlFn);
                            if (!pepxmlAsCreatedByFragger.equals(pepxmlWhereItShouldBe)) {
                                List<ProcessBuilder> pbsMove = ToolingUtils
                                        .pbsMoveFiles(jarFragpipe, pepxmlWhereItShouldBe.getParent(), true,
                                                Collections.singletonList(pepxmlAsCreatedByFragger));
                                pbis.addAll(PbiBuilder.from(pbsMove, NAME + " move pepxml"));
                            }
                        }
                    }

                    if (params.getShiftedIons() || fraggerOutputType.valueInParamsFile().contains("tsv")) {
                        List<Path> tsvWhereItShouldBeList = mapLcmsToTsv.get(f);
                        for (Path tsvWhereItShouldBe : tsvWhereItShouldBeList) {
                            String tsvFn = tsvWhereItShouldBe.getFileName().toString();
                            Path tsvAsCreatedByFragger = f.getPath().getParent().resolve(tsvFn);
                            if (!tsvAsCreatedByFragger.equals(tsvWhereItShouldBe)) {
                                List<ProcessBuilder> pbsMove = ToolingUtils
                                        .pbsMoveFiles(jarFragpipe, tsvWhereItShouldBe.getParent(), true,
                                                Collections.singletonList(tsvAsCreatedByFragger));
                                pbis.addAll(PbiBuilder.from(pbsMove, NAME + " move tsv"));
                            }
                        }
                    }

                    if (!f.getDataType().contentEquals("DDA") || fraggerOutputType.valueInParamsFile().contains("pin")) {
                        List<Path> pinWhereItShouldBeList = mapLcmsToPin.get(f);
                        for (Path pinWhereItShouldBe : pinWhereItShouldBeList) {
                            String pinFn = pinWhereItShouldBe.getFileName().toString();
                            Path pinAsCreatedByFragger = f.getPath().getParent().resolve(pinFn);
                            if (!pinAsCreatedByFragger.equals(pinWhereItShouldBe)) {
                                List<ProcessBuilder> pbsMove = ToolingUtils
                                        .pbsMoveFiles(jarFragpipe, pinWhereItShouldBe.getParent(), true,
                                                Collections.singletonList(pinAsCreatedByFragger));
                                pbis.addAll(PbiBuilder.from(pbsMove, NAME + " move pin"));
                            }
                        }
                    }
                }
            }
        }

        isConfigured = true;
        return true;
    }

    private void adjustDiaParams(MsfraggerParams params, MsfraggerParams paramsNew, String dataType) {
        paramsNew.setReportAlternativeProteins(true);
        paramsNew.setShiftedIons(false);
        paramsNew.setLabileSearchMode("off");
        paramsNew.setDeltamassAllowedResidues("all");
        paramsNew.setRemovePrecursorPeak(0);
        if (paramsNew.getCalibrateMass() > 1) {
            paramsNew.setCalibrateMass(1);
        }
        paramsNew.setMassDiffToVariableMod(0);
        paramsNew.setIsotopeError("0");
        paramsNew.setMassOffsets("0");
        paramsNew.setUseTopNPeaks(300);
        paramsNew.setMinimumRatio(0);
        paramsNew.setIntensityTransform(1);

        if (dataType.contentEquals("DIA")) {
            paramsNew.setDataType(1);
            paramsNew.setOutputReportTopN(Math.max(5, params.getOutputReportTopN()));
            paramsNew.setPrecursorTrueUnits(MassTolUnits.PPM);
            paramsNew.setPrecursorTrueTolerance(10);
            if (params.getPrecursorMassUnits() == PrecursorMassTolUnits.PPM) {
                paramsNew.setPrecursorMassLower(Math.max(-10, params.getPrecursorMassLower()));
                paramsNew.setPrecursorMassUpper(Math.min(10, params.getPrecursorMassUpper()));
            } else {
                paramsNew.setPrecursorMassUnits(PrecursorMassTolUnits.PPM);
                paramsNew.setPrecursorMassLower(-10.0);
                paramsNew.setPrecursorMassUpper(10.0);
            }
        } else if (dataType.contentEquals("GPF-DIA")) {
            paramsNew.setDataType(2);
            paramsNew.setOutputReportTopN(Math.max(3, params.getOutputReportTopN()));
        }
    }


    private static class GetSupportedExts {

        private List<Path> searchPaths;
        private List<String> desc;
        private List<String> exts;

        public GetSupportedExts(List<Path> searchPaths) {
            this.searchPaths = searchPaths;
        }

        public List<String> getDesc() {
            return desc;
        }

        public List<String> getExts() {
            return exts;
        }

        public GetSupportedExts invoke() {
            desc = new ArrayList<>(Arrays.asList("mzML", "mzXML", "mgf", "mzBIN"));
            exts = new ArrayList<>(Arrays.asList(".mgf", ".mzml", ".mzxml", ".mzbin"));
//      if (searchPaths != null && !searchPaths.isEmpty()) {
//        if (searchExtLibsThermo(searchPaths) != null) {
            desc.add("Thermo RAW");
            exts.add(".raw");
//        }
//        if (searchExtLibsBruker(searchPaths) != null) {
            desc.add("Buker PASEF .d");
            exts.add(".d");
//        }
//      }
            return this;
        }
    }
}