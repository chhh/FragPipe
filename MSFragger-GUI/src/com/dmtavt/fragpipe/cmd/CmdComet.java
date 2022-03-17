package com.dmtavt.fragpipe.cmd;

import com.dmtavt.fragpipe.Fragpipe;
import com.dmtavt.fragpipe.api.InputLcmsFile;
import com.dmtavt.fragpipe.tools.comet.CometParams;
import com.dmtavt.fragpipe.tools.enums.MassTolUnits;
import com.dmtavt.fragpipe.tools.enums.PrecursorMassTolUnits;
import com.dmtavt.fragpipe.tools.fragger.MsfraggerParams;
import com.github.chhh.utils.StringUtils;
import com.github.chhh.utils.UsageTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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
    
    public boolean configure(Component comp, boolean isDryRun, Path jarFragpipe, UsageTrigger binComet, String pathFasta, String cometParamsPath, int ramGb, List<InputLcmsFile> lcmsFiles, final String decoyTag, boolean hasDda, boolean hasDia, boolean hasGpfDia, boolean hasDiaLib, boolean isRunDiaU) {

        initPreConfig();
        if (StringUtils.isNullOrWhitespace(binComet.getBin())) {
            showError("Binary for running Comet can not be an empty string.", comp);
            return false;
        }
        if (testFilePath(binComet.getBin(), "") == null) {
            showError("Binary for running Fragger not found or could not be run.\nNeither on PATH, nor in the working directory", comp);
            return false;
        }

        boolean isOpenFormats = lcmsFiles.stream().map(lcms -> lcms.getPath().toString().toLowerCase(Locale.ROOT))
                .allMatch(f -> f.endsWith(".mzml") || f.endsWith(".mzxml"));
        if (!isOpenFormats) {
            showError("Comet only supports mzml and mzxml", comp);
            return false;
        }

        // Fasta file
        if (pathFasta == null) {
            showError("Fasta file path (Fragger) can't be empty", comp);
            return false;
        }

        if ((hasDia || hasGpfDia || hasDiaLib) && !isRunDiaU) {
            showError("Comet can't process DIA data natively, enable DIA-Umpire", comp);
            return false;
        }

        // Search parameter file
        if (!Paths.get(cometParamsPath).toFile().exists()) {
            showError("Comet can't process DIA data natively, enable DIA-Umpire", comp);
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

        StringBuilder sb = new StringBuilder();

        Map<InputLcmsFile, List<Path>> mapLcmsToPepxml = outputs(lcmsFiles, "pepXML", wd);
//        Map<InputLcmsFile, List<Path>> mapLcmsToTsv = outputs(lcmsFiles, "tsv", wd);
//        Map<InputLcmsFile, List<Path>> mapLcmsToPin = outputs(lcmsFiles, "pin", wd);


        Map<String, List<InputLcmsFile>> t = new TreeMap<>();

        for (InputLcmsFile inputLcmsFile : lcmsFiles) {
            String key = inputLcmsFile.getDataType();
            if ("DIA-Lib".equals(key)) {
                key = "DIA"; // merge DIA-Lib and DIA keys
            }
            t.computeIfAbsent(key, k -> new ArrayList<>()).add(inputLcmsFile);
        }

        for (Map.Entry<String, List<InputLcmsFile>> e : t.entrySet()) {
            int fileIndex = 0;
            while (fileIndex < e.getValue().size()) {
                List<String> cmd = new ArrayList<>();
                cmd.add(binComet.useBin());

                // check if the command length is ok so far
                sb.append(String.join(" ", cmd));
                if (sb.length() > commandLenLimit) {
                    showError("Comet command line length too large even for a single file.", comp);
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

                pb.directory(wd.toFile());

                pbis.add(PbiBuilder.from(pb));
                sb.setLength(0);

                CometParams cometParams = new CometParams();
                try {
                    cometParams.load(new FileInputStream(cometParamsPath), true);
                } catch (FileNotFoundException ex) {
                    showError("Comet params file does not exist: " + cometParamsPath, comp);
                    return false;
                } catch (IOException ex) {
                    showError("Could not read Comet params file", comp);
                    return false;
                }
                final String cometPepxmlOut = cometParams.getProps().getProp("output_pepxmlfile", "1").value;
                final boolean isOutputPepXml = "1".equals(cometPepxmlOut);

                // move the pepxml files if the output directory is not the same as where
                // the lcms files were
                for (InputLcmsFile f : addedLcmsFiles) {
                    if (isOutputPepXml) {
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

    public String getOutputFileExt() {
        return "pepXML";
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
            desc = new ArrayList<>(Arrays.asList("mzML", "mzXML"));
            exts = new ArrayList<>(Arrays.asList(".mgf", ".mzml"));
            return this;
        }
    }
}