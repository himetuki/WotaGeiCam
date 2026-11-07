package com.wotagei.cam.stance

/**
 * 极简 JSON 解析（递归下降，~100 行）：站位模型组只需要
 * `{"coef":[…],"intercept":…,"meta":{…}}` 与 `{"bands":{"side":[lo,hi]}}` 两种
 * 扁平结构。自带解析的原因：org.json 在 JVM 单测是 stub（工程无 Robolectric），
 * 自带解析让判定核心全链纯 JVM 可测；端侧行为与 PC 端 json.loads 语义对齐。
 * 支持对象/数组/数字/字符串/true/false/null；不支持嵌套转义的奇技淫巧（模型组
 * 产物由 train_head.py/calibrate.py 生成，格式受控）。
 */
internal object StanceJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.parseValue()
        p.skipWs()
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): Any? {
            require(i < s.length) { "JSON 意外结束" }
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> parseNumber()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.regionMatches(i, word, 0, word.length)) { "JSON 词法错误 @$i" }
            i += word.length
            return v
        }

        fun parseObject(): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                require(i < s.length && s[i] == ':') { "JSON 缺冒号 @$i" }
                i++
                skipWs()
                m[key] = parseValue()
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("JSON 对象未闭合 @$i")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            i++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                skipWs()
                l.add(parseValue())
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == ']' -> { i++; return l }
                    else -> throw IllegalArgumentException("JSON 数组未闭合 @$i")
                }
            }
        }

        private fun parseString(): String {
            require(s[i] == '"') { "JSON 字符串应从引号开始 @$i" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++
                        when (val e = s[i]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        private fun parseNumber(): Double {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
            return s.substring(start, i).toDouble()
        }
    }
}
