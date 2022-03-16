package com.dmtavt.fragpipe.tools.comet;

import com.dmtavt.fragpipe.tools.fragger.MsfraggerParams;

public enum CometOutputType {

    PEPXML("pepXML", "pepXML"),
    TSV("tsv", "tsv"),
    TSV_PEPXML("pepXML", "tsv_pepXML"),
    PIN("pin", "pin"),
    TSV_PIN("tsv", "tsv_pin"),
    PEPXML_PIN("pepXML", "pepXML_pin"),
    TSV_PEPXML_PIN("pepXML", "tsv_pepXML_pin");


    String extension;
    String valueInParamsFile;

    private CometOutputType(String extension, String valueInParamsFile) {
        this.extension = extension;
        this.valueInParamsFile = valueInParamsFile;
    }

    public String valueInParamsFile() {
        return valueInParamsFile;
    }

    public String getExtension() {
        return extension;
    }

    public static com.dmtavt.fragpipe.tools.enums.FraggerOutputType fromValueInParamsFile(String val) {
        for (com.dmtavt.fragpipe.tools.enums.FraggerOutputType t : com.dmtavt.fragpipe.tools.enums.FraggerOutputType.values()) {
            if (t.valueInParamsFile().equalsIgnoreCase(val))
                return t;
        }
        throw new IllegalStateException("Unknown output format stored in properties (property '"
                + MsfraggerParams.PROP_output_format + "')");
    }
}
