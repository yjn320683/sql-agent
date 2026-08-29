package com.yjn.sqlagent.realtime.model;

import javax.validation.constraints.NotBlank;

public class ServerRequest {
    @NotBlank(message = "Server 名称不能为空")
    private String name;
    @NotBlank(message = "连接地址不能为空")
    private String address;
    private String databaseName;
    @NotBlank(message = "数据库缩写不能为空")
    private String databaseAbbr;
    private String account;
    private String password;
    private String description;

    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getAddress() { return address; }
    public void setAddress(String value) { address = value; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String value) { databaseName = value; }
    public String getDatabaseAbbr() { return databaseAbbr; }
    public void setDatabaseAbbr(String value) { databaseAbbr = value; }
    public String getAccount() { return account; }
    public void setAccount(String value) { account = value; }
    public String getPassword() { return password; }
    public void setPassword(String value) { password = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
}
