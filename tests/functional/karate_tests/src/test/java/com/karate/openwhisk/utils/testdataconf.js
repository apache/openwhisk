/**
 * Usage: Return Random Email Id.
 */
exports.getRandomEmail = function () {
    var strValues = "abcdefghijk123456789";
    var strEmail = "";
    for (var i = 0; i < strValues.length; i++) {
        strEmail = strEmail + strValues.charAt(Math.round(strValues.length * Math.random()));
    }
    return strEmail + "@mymail.test";
};

/**
 * Usage: Generate random string.
 * characterLength :  Length of string.
 * Returns : Random string.
 */
exports.getRandomString = function (characterLength) {
    var randomText = "";
    var possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    for (var i = 0; i < characterLength; i++)
        randomText += possible.charAt(Math.floor(Math.random() * possible.length));
    return randomText;
};

/**
 * Usage: Generate random number.
 * characterLength :  Length of number.
 * Returns : Random number.
 */
exports.getRandomNumber = function (numberLength) {
    var randomNumber = "";
    var possible = "0123456789";
    for (var i = 0; i < numberLength; i++)
        randomNumber += possible.charAt(Math.floor(Math.random() * possible.length));
    return randomNumber;
};
