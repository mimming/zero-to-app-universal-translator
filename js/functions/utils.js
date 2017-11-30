exports.getLanguageWithoutLocale = function(languageCode) {
    if (languageCode.indexOf("-") >= 0) {
        return languageCode.substring(0, languageCode.indexOf("-"));
    }
    return languageCode;
}