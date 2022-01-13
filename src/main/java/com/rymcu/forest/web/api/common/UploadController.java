package com.rymcu.forest.web.api.common;

import com.rymcu.forest.core.result.GlobalResult;
import com.rymcu.forest.core.result.GlobalResultGenerator;
import com.rymcu.forest.dto.LinkToImageUrlDTO;
import com.rymcu.forest.dto.TokenUser;
import com.rymcu.forest.jwt.def.JwtConstants;
import com.rymcu.forest.service.ForestFileService;
import com.rymcu.forest.util.FileUtils;
import com.rymcu.forest.util.SpringContextHolder;
import com.rymcu.forest.util.UserUtils;
import com.rymcu.forest.util.Utils;
import com.rymcu.forest.web.api.exception.BaseApiException;
import com.rymcu.forest.web.api.exception.ErrorCode;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;

/**
 * 文件上传控制器
 *
 * @author ronger
 */
@RestController
@RequestMapping("/api/v1/upload")
public class UploadController {

    private final static String UPLOAD_SIMPLE_URL = "/api/upload/file";
    private final static String UPLOAD_URL = "/api/upload/file/batch";
    private final static String LINK_TO_IMAGE_URL = "/api/upload/file/link";

    private static Environment env = SpringContextHolder.getBean(Environment.class);

    @Resource
    private ForestFileService forestFileService;

    private static String getTypePath(Integer type) {
        String typePath;
        switch (type) {
            case 0:
                typePath = "avatar";
                break;
            case 1:
                typePath = "article";
                break;
            case 2:
                typePath = "tags";
                break;
            default:
                typePath = "images";
        }
        return typePath;
    }

    public static String uploadBase64File(String fileStr, Integer type) {
        if (StringUtils.isBlank(fileStr)) {
            return "";
        }
        String typePath = getTypePath(type);
        //图片存储路径
        String ctxHeadPicPath = env.getProperty("resource.pic-path");
        String dir = ctxHeadPicPath + "/" + typePath;
        File file = new File(dir);
        if (!file.exists()) {
            file.mkdirs();// 创建文件根目录
        }

        String localPath = Utils.getProperty("resource.file-path") + "/" + typePath + "/";
        String fileName = System.currentTimeMillis() + ".png";
        String savePath = file.getPath() + File.separator + fileName;
        File saveFile = new File(savePath);
        try {
            FileCopyUtils.copy(Base64.decodeBase64(fileStr.substring(fileStr.indexOf(",") + 1)), saveFile);
            fileStr = localPath + fileName;
        } catch (IOException e) {
            fileStr = "上传失败!";
        }
        return fileStr;
    }

    /**
     * 从输入流中获取字节数组
     *
     * @param inputStream
     * @return
     * @throws IOException
     */
    public static byte[] readInputStream(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[1024];
        int len = 0;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while ((len = inputStream.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        bos.close();
        return bos.toByteArray();
    }

    @PostMapping("/file")
    @Transactional(rollbackFor = Exception.class)
    public GlobalResult uploadPicture(@RequestParam(value = "file", required = false) MultipartFile multipartFile, @RequestParam(defaultValue = "1") Integer type, HttpServletRequest request) throws IOException, BaseApiException {
        if (multipartFile == null) {
            return GlobalResultGenerator.genErrorResult("请选择要上传的文件");
        }
        //todo 无法获取当前登录用户
//        User user = UserUtils.getCurrentUserByToken();
//        if (Objects.isNull(user)) {
//            throw new BaseApiException(ErrorCode.INVALID_TOKEN);
//        }
        Map data = new HashMap(2);
        String md5 = DigestUtils.md5DigestAsHex(multipartFile.getInputStream());
        String fileUrl = forestFileService.getFileUrlByMd5(md5);
        if (StringUtils.isNotEmpty(fileUrl)) {
            data.put("url", fileUrl);
            return GlobalResultGenerator.genSuccessResult(data);
        }
        String typePath = getTypePath(type);
        //图片存储路径
        String ctxHeadPicPath = env.getProperty("resource.pic-path");
        String dir = ctxHeadPicPath + "/" + typePath;
        File file = new File(dir);
        if (!file.exists()) {
            file.mkdirs();// 创建文件根目录
        }

        String localPath = Utils.getProperty("resource.file-path") + "/" + typePath + "/";

        String orgName = multipartFile.getOriginalFilename();
        String fileName = System.currentTimeMillis() + "." + FileUtils.getExtend(orgName).toLowerCase();

        String savePath = file.getPath() + File.separator + fileName;
        fileUrl = localPath + fileName;
        File saveFile = new File(savePath);
        try {
            FileCopyUtils.copy(multipartFile.getBytes(), saveFile);
            forestFileService.insertForestFile(fileUrl, savePath, md5, 1);
            data.put("url", fileUrl);
        } catch (IOException e) {
            data.put("message", "上传失败!");
        }
        return GlobalResultGenerator.genSuccessResult(data);

    }

    @PostMapping("/file/batch")
    @Transactional(rollbackFor = Exception.class)
    public GlobalResult batchFileUpload(@RequestParam(value = "file[]", required = false) MultipartFile[] multipartFiles, @RequestParam(defaultValue = "1") Integer type, HttpServletRequest request) throws BaseApiException {
        //todo 无法获取当前登录用户
//        User user = UserUtils.getCurrentUserByToken();
//        if (Objects.isNull(user)) {
//            throw new BaseApiException(ErrorCode.INVALID_TOKEN);
//        }
        String typePath = getTypePath(type);
        //图片存储路径
        String ctxHeadPicPath = env.getProperty("resource.pic-path");
        String dir = ctxHeadPicPath + "/" + typePath;
        File file = new File(dir);
        if (!file.exists()) {
            file.mkdirs();// 创建文件根目录
        }

        String localPath = Utils.getProperty("resource.file-path") + "/" + typePath + "/";
        Map succMap = new HashMap(10);
        Set errFiles = new HashSet();

        for (int i = 0, len = multipartFiles.length; i < len; i++) {
            MultipartFile multipartFile = multipartFiles[i];
            String orgName = multipartFile.getOriginalFilename();
            String fileName = System.currentTimeMillis() + "." + FileUtils.getExtend(orgName).toLowerCase();
            String savePath = file.getPath() + File.separator + fileName;
            File saveFile = new File(savePath);
            try (InputStream in = multipartFiles[i].getInputStream();
                 OutputStream out = Files.newOutputStream(saveFile.toPath())) {
                String md5 = DigestUtils.md5DigestAsHex(in);
                String fileUrl = forestFileService.getFileUrlByMd5(md5);
                if (StringUtils.isNotEmpty(fileUrl)) {
                    succMap.put(orgName, fileUrl);
                    continue;
                }

                fileUrl = localPath + fileName;
                FileCopyUtils.copy(in, out);
                forestFileService.insertForestFile(fileUrl, savePath, md5, 1);
                succMap.put(orgName, localPath + fileName);
            } catch (IOException e) {
                errFiles.add(orgName);
            }


        }
        Map data = new HashMap(2);
        data.put("errFiles", errFiles);
        data.put("succMap", succMap);
        return GlobalResultGenerator.genSuccessResult(data);
    }

    @GetMapping("/simple/token")
    public GlobalResult uploadSimpleToken(HttpServletRequest request) throws BaseApiException {
        String authHeader = request.getHeader(JwtConstants.AUTHORIZATION);
        if (StringUtils.isBlank(authHeader)) {
            throw new BaseApiException(ErrorCode.UNAUTHORIZED);
        }
        TokenUser tokenUser = UserUtils.getTokenUser(authHeader);
        Map map = new HashMap(2);
        map.put("uploadToken", tokenUser.getToken());
        map.put("uploadURL", UPLOAD_SIMPLE_URL);
        map.put("linkToImageURL", LINK_TO_IMAGE_URL);
        return GlobalResultGenerator.genSuccessResult(map);
    }

    @GetMapping("/token")
    public GlobalResult uploadToken(HttpServletRequest request) throws BaseApiException {
        String authHeader = request.getHeader(JwtConstants.AUTHORIZATION);
        if (StringUtils.isBlank(authHeader)) {
            throw new BaseApiException(ErrorCode.UNAUTHORIZED);
        }
        TokenUser tokenUser = UserUtils.getTokenUser(authHeader);
        Map map = new HashMap(2);
        map.put("uploadToken", tokenUser.getToken());
        map.put("uploadURL", UPLOAD_URL);
        map.put("linkToImageURL", LINK_TO_IMAGE_URL);
        return GlobalResultGenerator.genSuccessResult(map);
    }

    @PostMapping("/file/link")
    @Transactional(rollbackFor = Exception.class)
    public GlobalResult linkToImageUrl(@RequestBody LinkToImageUrlDTO linkToImageUrlDTO) throws IOException, BaseApiException {
        //todo 无法获取当前登录用户
//        User user = UserUtils.getCurrentUserByToken();
//        if (Objects.isNull(user)) {
//            throw new BaseApiException(ErrorCode.INVALID_TOKEN);
//        }
        String url = linkToImageUrlDTO.getUrl();
        URL link = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) link.openConnection();
        //设置超时间为3秒
        conn.setConnectTimeout(3 * 1000);
        //防止屏蔽程序抓取而返回403错误
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.75 Safari/537.36");
        conn.setRequestProperty("referer", "");

        //得到输入流
        InputStream inputStream = conn.getInputStream();

        // 获取文件md5值
        String md5 = DigestUtils.md5DigestAsHex(inputStream);
        String fileUrl = forestFileService.getFileUrlByMd5(md5);

        Map data = new HashMap(2);
        data.put("originalURL", url);

        if (StringUtils.isNotEmpty(fileUrl)) {
            data.put("url", fileUrl);
            return GlobalResultGenerator.genSuccessResult(data);
        }

        Integer type = linkToImageUrlDTO.getType();
        if (Objects.isNull(type)) {
            type = 1;
        }
        String typePath = getTypePath(type);
        //图片存储路径
        String ctxHeadPicPath = env.getProperty("resource.pic-path");
        String dir = ctxHeadPicPath + "/" + typePath;
        File file = new File(dir);
        if (!file.exists()) {
            file.mkdirs();// 创建文件根目录
        }


        String fileName = System.currentTimeMillis() + "." + FileUtils.getExtend(url);
        fileUrl = Utils.getProperty("resource.file-path") + "/" + typePath + "/" + fileName;

        String savePath = file.getPath() + File.separator + fileName;

        File saveFile = new File(savePath);
        try {
            //获取自己数组
            byte[] getData = readInputStream(inputStream);
            FileCopyUtils.copy(getData, saveFile);
            forestFileService.insertForestFile(fileUrl, savePath, md5, 1);
            data.put("originalURL", url);
            data.put("url", fileUrl);
        } catch (IOException e) {
            data.put("message", "上传失败!");
        }
        return GlobalResultGenerator.genSuccessResult(data);

    }

}
