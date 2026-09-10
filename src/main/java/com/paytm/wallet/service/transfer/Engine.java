package com.paytm.wallet.service.transfer;

/** The three interchangeable transfer engines. {@code configValue} is the {@code wallet.transfer.engine} setting. */
public enum Engine {

    CONDITIONAL_UPDATE("conditional-update"),
    SELECT_FOR_UPDATE("select-for-update"),
    SERIALIZABLE("serializable");

    private final String configValue;

    Engine(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }
}
