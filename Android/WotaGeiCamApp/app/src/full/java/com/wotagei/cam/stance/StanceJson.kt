package com.wotagei.cam.stance

/**
 * 模型组 JSON 的有界递归下降解析：端侧与 JVM 测试共用，不依赖 org.json 的平台 stub。
 * 导入包不是可信生成物：拒绝重复键、尾随内容、非有限数和过深嵌套，不能靠调用方 catch
 * 去兜 StackOverflowError。三个 JSON 文件共用同一边界。
 */
internal object StanceJson {
    const val MAX_TEXT_CHARS = 64 * 1024
    private const val MAX_DEPTH = 32

    fun parse(text: String): Any? {
        require(text.length <= MAX_TEXT_CHARS) { "模型 JSON 超过体积上限" }
        val p = Parser(text)
        p.skipWs()
        val v = p.parseValue(0)
        p.skipWs()
        require(p.i == text.length) { "JSON 含尾随内容 @${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun parseValue(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "模型 JSON 嵌套过深" }
            require(i < s.length) { "JSON 意外结束" }
            return when (s[i]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
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

        private fun parseObject(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                val key = parseString()
                require(!m.containsKey(key)) { "JSON 重复键：$key" }
                skipWs()
                require(i < s.length && s[i] == ':') { "JSON 缺冒号 @$i" }
                i++
                skipWs()
                m[key] = parseValue(depth)
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("JSON 对象未闭合 @$i")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                skipWs()
                l.add(parseValue(depth))
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == ']' -> { i++; return l }
                    else -> throw IllegalArgumentException("JSON 数组未闭合 @$i")
                }
            }
        }

        private fun parseString(): String {
            require(i < s.length && s[i] == '"') { "JSON 字符串应从引号开始 @$i" }
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "JSON 转义意外结束" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "JSON Unicode 转义不完整" }
                                val code = s.substring(i, i + 4).toIntOrNull(16)
                                require(code != null) { "JSON Unicode 转义非法" }
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("JSON 转义非法 @$i")
                        }
                    }
                    else -> {
                        require(c >= ' ') { "JSON 字符串含未转义控制符" }
                        sb.append(c)
                    }
                }
            }
            throw IllegalArgumentException("JSON 字符串未闭合")
        }

        private fun digits() {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            require(i > start) { "JSON 数字缺位 @$i" }
        }

        private fun parseNumber(): Double {
            val start = i
            if (i < s.length && s[i] == '-') i++
            require(i < s.length) { "JSON 数字意外结束" }
            if (s[i] == '0') i++ else {
                require(s[i] in '1'..'9') { "JSON 数字非法 @$i" }
                digits()
            }
            if (i < s.length && s[i] == '.') { i++; digits() }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                digits()
            }
            val v = s.substring(start, i).toDouble()
            require(v.isFinite()) { "JSON 数字超出有限范围" }
            return v
        }
    }
}
