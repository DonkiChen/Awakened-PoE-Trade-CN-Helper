object Config {
    /**
     * 使用打过 A 大汉化补丁的国际服 stat_descriptions 文件;
     * 一般在国服没开时, 会启用该选项;
     *
     * - true: 使用打过汉化补丁的国际服游戏数据生成 stats.ndjson 和 items.ndjson;
     * - false: 使用国服原版数据生成
     */
    const val usePatchedIntlStatDescriptionFiles = false
}
