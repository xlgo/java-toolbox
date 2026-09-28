package com.aqishi.toolbox.feature.security.infra.acme;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/** Resolves TXT records, used to check that a DNS-01 record is visible before asking the CA. */
@FunctionalInterface
public interface DnsTxtLookup {

    /** TXT values of {@code name} (quotes and segment separators removed); empty when the name does not exist. */
    List<String> lookup(String name) throws Exception;

    /** Lookup through the system resolvers via the JDK JNDI DNS provider. */
    static DnsTxtLookup system() {
        return name -> {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "2000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            DirContext ctx = new InitialDirContext(env);
            List<String> values = new ArrayList<>();
            try {
                Attributes attrs = ctx.getAttributes(name, new String[]{"TXT"});
                Attribute txt = attrs.get("TXT");
                if (txt != null) {
                    NamingEnumeration<?> all = txt.getAll();
                    while (all.hasMore()) {
                        values.add(normalize(String.valueOf(all.next())));
                    }
                }
            } catch (NameNotFoundException absent) {
                return values;
            } finally {
                ctx.close();
            }
            return values;
        };
    }

    /** {@code "ab" "cd"} becomes {@code abcd}; a bare value is returned unchanged. */
    static String normalize(String raw) {
        String s = raw.trim();
        if (s.startsWith("\"")) {
            s = s.replace("\" \"", "").replace("\"", "");
        }
        return s;
    }
}
