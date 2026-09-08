/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.meizucamera.filters;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class FilterProvider extends ContentProvider {
    private static final String SUPPORT_AUTHORITY = "com.meizu.media.gallery.filtercomp";
    private static final String CLASSIC_AUTHORITY = "com.meizu.media.gallery.classic-filtermanager";
    private static final String USER_AUTHORITY = "com.meizu.media.gallery.filtermanager";

    private static final String[] SUPPORT_COLUMNS = {"supp"};
    private static final String[] CLASSIC_COLUMNS = {"id", "name", "file_name", "front_file_name"};
    private static final String[] USER_COLUMNS = {
        "_id", "name", "name_tc", "name_en", "md5", "position", "inner"
    };
    private static final Set<String> SUPPORT_COLUMN_SET =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(SUPPORT_COLUMNS)));
    private static final Set<String> CLASSIC_COLUMN_SET =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(CLASSIC_COLUMNS)));
    private static final Set<String> USER_COLUMN_SET =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(USER_COLUMNS)));

    private static final Pattern CLASSIC_ASSET = Pattern.compile("classicFilter/[A-Za-z0-9_.-]+");
    private static final Pattern MD5 = Pattern.compile("[0-9a-f]{32}");

    private volatile Catalog mCatalog;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        rejectSelection(selection, selectionArgs);

        String authority = requireAuthority(uri);
        requireBaseUri(uri);
        switch (authority) {
            case SUPPORT_AUTHORITY:
                rejectSortOrder(sortOrder);
                return supportCursor(projection);
            case CLASSIC_AUTHORITY:
                rejectSortOrder(sortOrder);
                return classicCursor(projection);
            case USER_AUTHORITY:
                if (!TextUtils.isEmpty(sortOrder) && !"position DESC, _id ASC".equals(sortOrder)) {
                    throw new IllegalArgumentException("Unsupported sort order");
                }
                return userCursor(projection);
            default:
                throw new IllegalArgumentException("Unsupported authority");
        }
    }

    @Override
    public String getType(Uri uri) {
        return CLASSIC_AUTHORITY.equals(requireAuthority(uri)) ? "" : null;
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Read-only provider");
        }

        String authority = requireAuthority(uri);
        if (CLASSIC_AUTHORITY.equals(authority)) {
            int id = requireId(uri, 0, 15);
            boolean front = requireFrontParameter(uri);
            ClassicFilter filter = catalog().classic.get(id);
            return openAsset(front ? filter.frontFile : filter.file);
        }
        if (USER_AUTHORITY.equals(authority)) {
            int id = requireId(uri, 1, 11);
            requireNoQuery(uri);
            return openAsset("filterManager/" + catalog().user.get(id - 1).md5);
        }
        throw new FileNotFoundException("Unsupported URI");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    private Cursor supportCursor(String[] projection) {
        String[] columns = resolveProjection(projection, SUPPORT_COLUMNS, SUPPORT_COLUMN_SET);
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            row[i] = 1;
        }
        cursor.addRow(row);
        return cursor;
    }

    private Cursor classicCursor(String[] projection) {
        String[] columns = resolveProjection(projection, CLASSIC_COLUMNS, CLASSIC_COLUMN_SET);
        List<ClassicFilter> filters = catalog().classic;
        MatrixCursor cursor = new MatrixCursor(columns, filters.size());
        for (ClassicFilter filter : filters) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (columns[i]) {
                    case "id":
                        row[i] = filter.id;
                        break;
                    case "name":
                        row[i] = filter.name;
                        break;
                    case "file_name":
                        row[i] = filter.file;
                        break;
                    case "front_file_name":
                        row[i] = filter.frontFile;
                        break;
                    default:
                        throw new AssertionError();
                }
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    private Cursor userCursor(String[] projection) {
        String[] columns = resolveProjection(projection, USER_COLUMNS, USER_COLUMN_SET);
        List<UserFilter> filters = catalog().user;
        MatrixCursor cursor = new MatrixCursor(columns, filters.size());
        for (UserFilter filter : filters) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (columns[i]) {
                    case "_id":
                        row[i] = filter.id;
                        break;
                    case "name":
                        row[i] = filter.name;
                        break;
                    case "name_tc":
                        row[i] = filter.nameTc;
                        break;
                    case "name_en":
                        row[i] = filter.nameEn;
                        break;
                    case "md5":
                        row[i] = filter.md5;
                        break;
                    case "position":
                        row[i] = 0;
                        break;
                    case "inner":
                        row[i] = 1;
                        break;
                    default:
                        throw new AssertionError();
                }
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    private AssetFileDescriptor openAsset(String path) throws FileNotFoundException {
        try {
            return attachedContext().getAssets().openFd(path);
        } catch (IOException e) {
            FileNotFoundException exception = new FileNotFoundException(path);
            exception.initCause(e);
            throw exception;
        }
    }

    private Catalog catalog() {
        Catalog result = mCatalog;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            result = mCatalog;
            if (result == null) {
                try {
                    result = loadCatalog();
                } catch (IOException | JSONException e) {
                    throw new IllegalStateException("Invalid filter catalog", e);
                }
                mCatalog = result;
            }
        }
        return result;
    }

    private Catalog loadCatalog() throws IOException, JSONException {
        JSONObject root = new JSONObject(readAsset("filters.json"));
        JSONArray classicJson = root.getJSONArray("classic");
        JSONArray userJson = root.getJSONArray("user");
        if (classicJson.length() != 16 || userJson.length() != 11) {
            throw new JSONException("Unexpected filter count");
        }

        ClassicFilter[] classic = new ClassicFilter[16];
        for (int i = 0; i < classicJson.length(); i++) {
            JSONObject item = classicJson.getJSONObject(i);
            int id = item.getInt("id");
            if (id < 0 || id >= classic.length || classic[id] != null) {
                throw new JSONException("Invalid classic filter id");
            }
            String file = requireClassicAsset(item.getString("file"));
            String frontFile = requireClassicAsset(item.getString("front"));
            classic[id] = new ClassicFilter(id, requireText(item, "name"), file, frontFile);
        }

        UserFilter[] user = new UserFilter[11];
        for (int i = 0; i < userJson.length(); i++) {
            JSONObject item = userJson.getJSONObject(i);
            int id = item.getInt("id");
            if (id < 1 || id > user.length || user[id - 1] != null) {
                throw new JSONException("Invalid user filter id");
            }
            String md5 = item.getString("md5");
            if (!MD5.matcher(md5).matches()) {
                throw new JSONException("Invalid user filter digest");
            }
            user[id - 1] =
                    new UserFilter(
                            id,
                            requireText(item, "name"),
                            requireText(item, "nameTc"),
                            requireText(item, "nameEn"),
                            md5);
        }
        return new Catalog(
                Collections.unmodifiableList(Arrays.asList(classic)),
                Collections.unmodifiableList(Arrays.asList(user)));
    }

    private String readAsset(String path) throws IOException {
        StringBuilder result = new StringBuilder();
        try (InputStream stream = attachedContext().getAssets().open(path);
                InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                result.append(buffer, 0, count);
            }
        }
        return result.toString();
    }

    private Context attachedContext() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Provider is not attached");
        }
        return context;
    }

    private static String requireAuthority(Uri uri) {
        String authority = uri.getAuthority();
        if (!SUPPORT_AUTHORITY.equals(authority)
                && !CLASSIC_AUTHORITY.equals(authority)
                && !USER_AUTHORITY.equals(authority)) {
            throw new IllegalArgumentException("Unsupported authority");
        }
        return authority;
    }

    private static void requireBaseUri(Uri uri) {
        if (!uri.getPathSegments().isEmpty()
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Unsupported URI");
        }
    }

    private static int requireId(Uri uri, int minimum, int maximum) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 1 || uri.getFragment() != null) {
            throw new FileNotFoundException("Invalid filter URI");
        }
        String segment = segments.get(0);
        if (segment.isEmpty() || (segment.length() > 1 && segment.charAt(0) == '0')) {
            throw new FileNotFoundException("Invalid filter id");
        }
        int id;
        try {
            id = Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            throw new FileNotFoundException("Invalid filter id");
        }
        if (id < minimum || id > maximum) {
            throw new FileNotFoundException("Unknown filter id");
        }
        return id;
    }

    private static boolean requireFrontParameter(Uri uri) throws FileNotFoundException {
        Set<String> names = uri.getQueryParameterNames();
        if (names.isEmpty()) {
            return false;
        }
        if (names.size() != 1 || !names.contains("front")) {
            throw new FileNotFoundException("Invalid filter URI");
        }
        List<String> values = uri.getQueryParameters("front");
        if (values.size() != 1
                || !("true".equals(values.get(0)) || "false".equals(values.get(0)))) {
            throw new FileNotFoundException("Invalid front parameter");
        }
        return "true".equals(values.get(0));
    }

    private static void requireNoQuery(Uri uri) throws FileNotFoundException {
        if (uri.getQuery() != null) {
            throw new FileNotFoundException("Invalid filter URI");
        }
    }

    private static String[] resolveProjection(
            String[] projection, String[] defaults, Set<String> allowed) {
        if (projection == null) {
            return defaults.clone();
        }
        if (projection.length == 0) {
            throw new IllegalArgumentException("Empty projection");
        }
        for (String column : projection) {
            if (!allowed.contains(column)) {
                throw new IllegalArgumentException("Unsupported column: " + column);
            }
        }
        return projection.clone();
    }

    private static void rejectSelection(String selection, String[] selectionArgs) {
        if (!TextUtils.isEmpty(selection) || (selectionArgs != null && selectionArgs.length != 0)) {
            throw new IllegalArgumentException("Selection is not supported");
        }
    }

    private static void rejectSortOrder(String sortOrder) {
        if (!TextUtils.isEmpty(sortOrder)) {
            throw new IllegalArgumentException("Sort order is not supported");
        }
    }

    private static String requireText(JSONObject item, String key) throws JSONException {
        String value = item.getString(key);
        if (value.isEmpty()) {
            throw new JSONException("Empty " + key);
        }
        return value;
    }

    private static String requireClassicAsset(String path) throws JSONException {
        if (!CLASSIC_ASSET.matcher(path).matches()) {
            throw new JSONException("Invalid classic asset path");
        }
        return path;
    }

    private static final class Catalog {
        final List<ClassicFilter> classic;
        final List<UserFilter> user;

        Catalog(List<ClassicFilter> classic, List<UserFilter> user) {
            this.classic = classic;
            this.user = user;
        }
    }

    private static final class ClassicFilter {
        final int id;
        final String name;
        final String file;
        final String frontFile;

        ClassicFilter(int id, String name, String file, String frontFile) {
            this.id = id;
            this.name = name;
            this.file = file;
            this.frontFile = frontFile;
        }
    }

    private static final class UserFilter {
        final int id;
        final String name;
        final String nameTc;
        final String nameEn;
        final String md5;

        UserFilter(int id, String name, String nameTc, String nameEn, String md5) {
            this.id = id;
            this.name = name;
            this.nameTc = nameTc;
            this.nameEn = nameEn;
            this.md5 = md5;
        }
    }
}
