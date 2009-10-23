package org.apache.log4j;

import java.util.Map;

/**
 * Provides method to do placeholder replacement much as the way Spring handles
 * similar job using
 * {@link org.springframework.beans.factory.config.PropertyPlaceholderConfigurer}
 * .
 * 
 * @author cheng.lee@gmail.com (Cheng Lee)
 */
public class PlaceholderUtil {
  /**
   * Do placeholder replacement on the {@code input} String, using the specified
   * {@code prefix}, {@code suffix} to find the placeholder name and then
   * retrieves the value for it from the {@code placeholderValues}. This
   * operation supports environment variables as well so as to provide override
   * behavior: set {@code envVarOverridesUserValues} to true for environment
   * variables to take precedence.
   * 
   * @param input
   *          The input String containing the placeholders
   * @param prefix
   *          The prefix that marks the start of a placeholder key.
   * @param suffix
   *          The suffix that marks the end of a placeholder key.
   * @param map
   *          The {@@link Map} containing the placeholder key->value
   *          mapping
   * @param envVarOverridesUserValues
   * 
   * @return The input String with all placeholder values replaced.
   */
  public String replace(String input, String prefix, String suffix,
      Map placeholderValues, boolean envVarOverridesUserValues) {
    int prefixIndex = input.indexOf(prefix);
    if (prefixIndex == -1) {
      return input;
    }

    int suffixIndex = input.indexOf(suffix);
    if (prefixIndex == -1) {
      // No closing suffix, return idempotent instance
      return input;
    }

    // Have both prefix and suffix, do replacement
    String placeholder = input.substring(prefixIndex + prefix.length(),
        suffixIndex);
    String envValue = System.getenv(placeholder);
    String value = (String) placeholderValues.get(placeholder);

    // See if we have found the value for the placeholder
    if (envValue == null && value == null) {
      throw new IllegalArgumentException("Cannot resolve placeholder '"
          + placeholder + "'");
    }

    // Decide on whether to use Environment value or user provided value
    String finalValueForPlaceHolder = resolvePlaceholderValue(
        envVarOverridesUserValues, envValue, value);

    // See if we have nested placeholders
    String nestedExpression = replace(finalValueForPlaceHolder, prefix, suffix,
        placeholderValues, envVarOverridesUserValues);

    // Obtain before and after placeholder parts
    String stringBeforePrefix = input.substring(0, prefixIndex);
    String stringAfterSuffix = input.substring(suffixIndex + suffix.length());

    // Merge parts
    String mergedString = stringBeforePrefix + nestedExpression
        + stringAfterSuffix;

    // Do replacement for placeholders on the right hand side
    String finalExpression = replace(mergedString, prefix, suffix,
        placeholderValues, envVarOverridesUserValues);

    return finalExpression;
  }

  /**
   * Decides on which value take precedence: the user specified value or the
   * environment variable value. {@code envVarOverridesUserValues} sets the
   * order.
   * 
   * @param envVarOverridesUserValues if true then environment variables take precedence.
   * @param envValue The value as specified by environment variable.
   * @param userValue The value as provided by user.
   * 
   * @return A String representing the value which takes precedence.
   */
  private String resolvePlaceholderValue(boolean envVarOverridesUserValues,
      String envValue, String userValue) {
    if (envValue == null) {
      return userValue;
    }

    if (userValue == null) {
      return envValue;
    }

    String finalValueForPlaceHolder;

    if (envVarOverridesUserValues) {
      finalValueForPlaceHolder = userValue;
    }

    finalValueForPlaceHolder = envValue;

    if (!envVarOverridesUserValues) {
      finalValueForPlaceHolder = userValue;
    }

    return finalValueForPlaceHolder;
  }
}
