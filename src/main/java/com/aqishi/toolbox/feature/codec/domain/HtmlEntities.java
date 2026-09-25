package com.aqishi.toolbox.feature.codec.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * HTML 命名实体表：HTML 4.01 的全部 252 个实体再加上 {@code apos}。
 *
 * <p>HTML5 的完整表有两千多项，绝大多数在实际文本里从不出现；HTML4 这套覆盖了
 * Latin-1、常用标点、希腊字母与数学符号，足以处理日常从网页/邮件里复制出来的文本。
 * 表用紧凑字符串描述：{@code @N} 把当前码点设为 N，其后每个名字依次占用一个码点，
 * 这样连续的区段（Latin-1、希腊字母）不必逐个写码点。</p>
 */
final class HtmlEntities {

    private static final String TABLE = String.join(" ",
            "@34 quot @38 amp @39 apos @60 lt @62 gt",
            // Latin-1 补充区 U+00A0..U+00FF，按码点顺序连续排列
            "@160 nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr",
            "deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest",
            "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml",
            "ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig",
            "agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml",
            "eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml",
            "@338 OElig oelig @352 Scaron scaron @376 Yuml @402 fnof @710 circ @732 tilde",
            // 希腊字母：大写 U+0391..U+03A9 中间 U+03A2 空缺，小写 U+03B1..U+03C9 连续
            "@913 Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda Mu Nu Xi Omicron Pi Rho",
            "@931 Sigma Tau Upsilon Phi Chi Psi Omega",
            "@945 alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho",
            "sigmaf sigma tau upsilon phi chi psi omega @977 thetasym upsih @982 piv",
            "@8194 ensp emsp @8201 thinsp @8204 zwnj zwj lrm rlm @8211 ndash mdash @8216 lsquo rsquo sbquo",
            "@8220 ldquo rdquo bdquo @8224 dagger Dagger bull @8230 hellip @8240 permil @8242 prime Prime",
            "@8249 lsaquo rsaquo @8254 oline @8260 frasl @8364 euro @8465 image @8472 weierp @8476 real",
            "@8482 trade @8501 alefsym @8592 larr uarr rarr darr harr @8629 crarr @8656 lArr uArr rArr dArr hArr",
            "@8704 forall @8706 part exist @8709 empty @8711 nabla isin notin @8715 ni @8719 prod @8721 sum minus",
            "@8727 lowast @8730 radic @8733 prop infin @8736 ang @8743 and or cap cup int @8756 there4",
            "@8764 sim @8773 cong @8776 asymp @8800 ne equiv @8804 le ge @8834 sub sup nsub @8838 sube supe",
            "@8853 oplus @8855 otimes @8869 perp @8901 sdot @8968 lceil rceil lfloor rfloor",
            // lang/rang 在 HTML4 指向 U+2329/U+232A，HTML5 改为 U+27E8/U+27E9，这里跟随 HTML5
            "@10216 lang rang @9674 loz @9824 spades @9827 clubs @9829 hearts @9830 diams");

    private static final Map<String, Integer> BY_NAME;

    static {
        Map<String, Integer> map = new HashMap<>();
        int codePoint = 0;
        for (String token : TABLE.split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.charAt(0) == '@') {
                codePoint = Integer.parseInt(token.substring(1));
            } else {
                map.put(token, codePoint++);
            }
        }
        BY_NAME = Collections.unmodifiableMap(map);
    }

    private HtmlEntities() {
    }

    /** 按名字（区分大小写）查码点，没有返回 -1。 */
    static int lookup(String name) {
        Integer value = BY_NAME.get(name);
        return value == null ? -1 : value;
    }

    static int size() {
        return BY_NAME.size();
    }
}
