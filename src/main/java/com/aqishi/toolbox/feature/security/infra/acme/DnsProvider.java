package com.aqishi.toolbox.feature.security.infra.acme;

/** A DNS API able to create and delete the {@code _acme-challenge} TXT records of DNS-01. */
public interface DnsProvider {

    /** Creates a TXT record and returns an id that {@link #deleteTxtRecord} accepts. */
    String addTxtRecord(String domain, String recordName, String recordValue) throws Exception;

    void deleteTxtRecord(String domain, String recordId) throws Exception;
}
