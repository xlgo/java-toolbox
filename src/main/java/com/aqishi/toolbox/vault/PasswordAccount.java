package com.aqishi.toolbox.vault;

/** Password-manager record stored inside the encrypted vault payload. */
public class PasswordAccount {
    private String name;
    private String username = "";
    private String password = "";
    private String url = "";

    public PasswordAccount() {
    }

    public PasswordAccount(String name, String username, String password, String url) {
        setName(name);
        setUsername(username);
        setPassword(password);
        setUrl(url);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = optional(username);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = optional(password);
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = optional(url);
    }

    public PasswordAccount copy() {
        return new PasswordAccount(name, username, password, url);
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }
}
