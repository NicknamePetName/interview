package com.yixin.interview.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yixin.interview.common.ErrorCode;
import com.yixin.interview.constant.CommonConstant;
import com.yixin.interview.constant.RedisConstant;
import com.yixin.interview.constant.UserConstant;
import com.yixin.interview.exception.BusinessException;
import com.yixin.interview.exception.ThrowUtils;
import com.yixin.interview.mapper.UserMapper;
import com.yixin.interview.model.dto.user.UserAddRequest;
import com.yixin.interview.model.dto.user.UserQueryRequest;
import com.yixin.interview.model.entity.User;
import com.yixin.interview.model.enums.UserRoleEnum;
import com.yixin.interview.model.vo.LoginUserVO;
import com.yixin.interview.model.vo.UserVO;
import com.yixin.interview.service.UserService;
import com.yixin.interview.utils.DeviceUtils;
import com.yixin.interview.utils.SqlUtils;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.bean.WxOAuth2UserInfo;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBitSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.Year;
import java.util.*;
import java.util.stream.Collectors;

import static com.yixin.interview.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 用户服务实现
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Resource
    private RedissonClient redissonClient;

    /**
     * 盐值，混淆密码
     */
    public static final String SALT = "yixin";

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        // 密码和校验密码相同
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        synchronized (userAccount.intern()) {
            // 账户不能重复
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("userAccount", userAccount);
            long count = this.baseMapper.selectCount(queryWrapper);
            if (count > 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
            }
            // 2. 加密
            String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
            // 3. 插入数据
            User user = new User();
            user.setUserAccount(userAccount);
            user.setUserPassword(encryptPassword);
            boolean saveResult = this.save(user);
            if (!saveResult) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
            }
            return user.getId();
        }
    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号错误");
        }
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
        }
        // 2. 加密
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
        // 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", encryptPassword);
        User user = this.baseMapper.selectOne(queryWrapper);
        // 用户不存在
        if (user == null) {
            log.info("user login failed, userAccount cannot match userPassword");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        if (user.getUserRole().equals(UserConstant.BAN_ROLE)) {
            log.info("user login failed, userAccount is BAN");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "您的账号已被封禁，无法访问此资源");
        }
        // 3. 记录用户的登录态
//        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        // 使用 Sa-Token 登录,并指定设备，同端登录互斥
        StpUtil.login(user.getId(), DeviceUtils.getRequestDevice(request));
        StpUtil.getSession().set(USER_LOGIN_STATE, user);
        return this.getLoginUserVO(user);
    }

    @Override
    public LoginUserVO userLoginByMpOpen(WxOAuth2UserInfo wxOAuth2UserInfo, HttpServletRequest request) {
        String unionId = wxOAuth2UserInfo.getUnionId();
        String mpOpenId = wxOAuth2UserInfo.getOpenid();
        // 单机锁
        synchronized (unionId.intern()) {
            // 查询用户是否已存在
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("unionId", unionId);
            User user = this.getOne(queryWrapper);
            // 被封号，禁止登录
            if (user != null && UserRoleEnum.BAN.getValue().equals(user.getUserRole())) {
                throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "该用户已被封，禁止登录");
            }
            // 用户不存在则创建
            if (user == null) {
                user = new User();
                user.setUnionId(unionId);
                user.setMpOpenId(mpOpenId);
                user.setUserAvatar(wxOAuth2UserInfo.getHeadImgUrl());
                user.setUserName(wxOAuth2UserInfo.getNickname());
                boolean result = this.save(user);
                if (!result) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "登录失败");
                }
            }
            // 记录用户的登录态
            request.getSession().setAttribute(USER_LOGIN_STATE, user);
            return getLoginUserVO(user);
        }
    }

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 先判断是否已登录
        Object loginUserId = StpUtil.getLoginIdDefaultNull();
        if (loginUserId == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
//        Object object = StpUtil.getSession().get(UserConstant.USER_LOGIN_STATE); 使用下面方法查询，保证数据一定是及时更新的


/*        Object userObj = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
         从数据库查询（追求性能的话可以注释，直接走缓存）
        long userId = currentUser.getId();*/
        User currentUser = this.getById((String) loginUserId);
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    /**
     * 获取当前登录用户（允许未登录）
     *
     * @param request
     * @return
     */
    @Override
    public User getLoginUserPermitNull(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            return null;
        }
        // 从数据库查询（追求性能的话可以注释，直接走缓存）
        long userId = currentUser.getId();
        return this.getById(userId);
    }

    /**
     * 是否为管理员
     *
     * @param request
     * @return
     */
    @Override
    public boolean isAdmin(HttpServletRequest request) {
        // 仅管理员可查询
        // 基于 Sa-Token 改造
        Object userObj = StpUtil.getSession().get(USER_LOGIN_STATE);
//        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User user = (User) userObj;
        return isAdmin(user);
    }

    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }

    /**
     * 用户注销
     *
     * @param request
     */
    @Override
    public boolean userLogout(HttpServletRequest request) {

        StpUtil.checkLogin();
        StpUtil.logout();

        /*if (request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE) == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
        }
        // 移除登录态
        request.getSession().removeAttribute(UserConstant.USER_LOGIN_STATE);*/
        return true;
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtils.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVO(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String unionId = userQueryRequest.getUnionId();
        String mpOpenId = userQueryRequest.getMpOpenId();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String userAccount = userQueryRequest.getUserAccount();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();

        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(id != null, "id", id);
        queryWrapper.eq(StringUtils.isNotBlank(unionId), "unionId", unionId);
        queryWrapper.eq(StringUtils.isNotBlank(mpOpenId), "mpOpenId", mpOpenId);
        queryWrapper.eq(StringUtils.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.eq(StringUtils.isNotBlank(userAccount), "userAccount", userAccount != null ? userAccount.strip() : null);
        queryWrapper.like(StringUtils.isNotBlank(userProfile), "userProfile", userProfile != null ? userProfile.strip() : null);
        queryWrapper.like(StringUtils.isNotBlank(userName), "userName", userName != null ? userName.strip() : null);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    @Override
    public Long userAdd(UserAddRequest userAddRequest) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAddRequest.getUserAccount())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号不能为空");
        }
        if (userAddRequest.getUserAccount().length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        userAddRequest.setUserAccount(userAddRequest.getUserAccount().trim());
        userAddRequest.setUserName(userAddRequest.getUserName() != null ? userAddRequest.getUserName().strip() : null);
        userAddRequest.setUserAvatar(userAddRequest.getUserAvatar() != null ? userAddRequest.getUserAvatar().strip() : null);
        userAddRequest.setUserProfile(userAddRequest.getUserProfile() != null ? userAddRequest.getUserProfile().strip() : null);
        User user = new User();
        BeanUtils.copyProperties(userAddRequest, user);
        // 账户不能重复
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAddRequest.getUserAccount());
        long count = this.baseMapper.selectCount(queryWrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }
        // 默认密码 zt072433
        String defaultPassword = "zt072433";
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + defaultPassword).getBytes());
        user.setUserPassword(encryptPassword);
        boolean result = this.save(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return user.getId();
    }

    /**
     * 添加用户签到记录
     *
     * @param userId 用户 id
     * @return 当前用户是否已签到成功
     */
    @Override
    public boolean addUserSignIn(long userId) {
        LocalDate localDate = LocalDate.now();
        String key = RedisConstant.getUserSignInRedisKey(localDate.getYear(), userId);
        // 获取 Redis 的 BitMap
        RBitSet signInBitSet = redissonClient.getBitSet(key);
        // 获取当前日期是一年中的第几天，作为偏移量 (从 1 开始)
        int offset = localDate.getDayOfYear();
        // 判断是否已签到
        if (!signInBitSet.get(offset)) {
            // 未签到，设置为已签到
            signInBitSet.set(offset, true);

        }
        // 当天已签到
        return true;
    }

    /**
     * 获取用户某个年份签到记录
     *
     * @param userId 用户 id
     * @param year   年份 (为空则表示当前年份)
     * @return 签到记录映射
     */
    @Override
    public List<Integer> getUserSignInRecord(long userId, Integer year) {
        if (year == null) {
            LocalDate localDate = LocalDate.now();
            year = localDate.getYear();
        }
        String key = RedisConstant.getUserSignInRedisKey(year, userId);
        // 获取 Redis 的 BitMap
        RBitSet signInBitSet = redissonClient.getBitSet(key);
        // 加载 BitSet 到内存中，避免后续读取时发送多次请求
        BitSet bitSet = signInBitSet.asBitSet();
        // 构造返回结果
        List<Integer> dayList = new ArrayList<>();
        // 获取当年份的总天数
        int totalDays = Year.of(year).length();
        // 从索引 0 开始查找下一个被设置为 1 的位
        int index = bitSet.nextSetBit(0);
        while (index >= 0) {
            dayList.add(index);
            // 继续查找下一个被设置为 1 的位
            index = bitSet.nextSetBit(index + 1);
        }
        return dayList;
    }
}
