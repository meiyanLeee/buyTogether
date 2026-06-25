package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    IBlogService blogService;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    IUserService userService;

    @Resource
    IFollowService followService;
    /**
     * 查询首页比较火的博客
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {

        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::queryBlogUser);
        records.forEach(blog->{
            addIsLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查询当前博客的基本信息，包括博客信息和发布人信息，以及用户是否点过赞
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        queryBlogUser(blog);
        addIsLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 查询用户是否对这篇帖子点过赞
     * @param blog
     */
    private void addIsLiked(Blog blog) {
        // 当用户未登录时观看首页，用户还没有id，这时要查询该用户有没有点赞是查不到的
        // 干脆不写isLiked这个字段，直接返回
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO==null){
            return;
        }
        // 用户已经登录，可以正常查询是否已点赞某个帖子 isLiked字段
        // 前端接收后可以知道哪个点赞需要标红
        Long id = blog.getId();
        Long userId = UserHolder.getUser().getId();
        String likeKey = BLOG_LIKED_KEY+ id;
        Double score = stringRedisTemplate.opsForZSet().score(likeKey, userId.toString());
        blog.setIsLike(score!=null);
    }

    /**
     * 点赞操作
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
      if(UserHolder.getUser()==null){
          return Result.fail("请先登录在进行点赞");
      }
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前用户是否已经点赞
        String likeKey = BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(likeKey, userId.toString());
        boolean like = score==null;
        // 没点过赞，点赞
        if(like){
            // sql的点赞数 +1
            boolean isSuccess = update().setSql("liked = liked +1").eq("id", id).update();
            if(isSuccess){
                // 保存用户到set集合中
                stringRedisTemplate.opsForZSet().add(likeKey ,userId.toString(),System.currentTimeMillis());
            }
        }else{
            // sql的点赞数 -1
            boolean isSuccess = update().setSql("liked = liked -1").eq("id", id).update();
            if(isSuccess){
                // 保存用户到set集合中
                stringRedisTemplate.opsForZSet().remove(likeKey ,userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询top5早点赞的用户
     * @param id
     * @return
     */
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY+id;
        // 1.根据blog查询redis zset中前五个点过赞的用户id
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id zrange key 0 4
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 将ids中的数进行字符串拼接
        String sIds = StrUtil.join(",",ids);
        // 3.根据id查询用户  WHERE id IN (5,1) ORDER BY FIELD(id, 5, 1)
        // last 的意思就是在sql语句的最后拼上这条语句
        // 想要按照set中元素的顺序，通过order by field(id,5,1) 达到按点赞顺序排序的效果
        // 数据库会自动按照id排序
        List<UserDTO> users = userService
                .query().in("id",ids).last("ORDER BY FIELD(id,"+sIds+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 3.返回用户信息
        return Result.ok(users);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        blog.setUserId(user.getId());
        // 保存团长种草动态
        boolean isSuccess = blogService.save(blog);
        if(!isSuccess) {
            return Result.fail("发布团长种草失败");
        }

        // 将动态 id 推送到关注该团长的用户收件箱
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        for (Follow follow : follows) {
            String key = FEED_KEY+follow.getUserId();
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 在个人主页查看关注的所有博主的blog，通过滚动查询
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result followBlog(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        // 查询收件箱 ZREVRANGEBYSCORES key Max Min Limit offset count
        String key = FEED_KEY+userId;
        // tuple 的value是收件箱中blog的id，score是时间戳
        // offset等于0时，数据从score小于等于max的第一条数据开始返回
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 非空判断
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        // 最后要返回一个blog的List，offset，minTime
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        Integer offset1 = 1;
        long minTime = 0;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            blogIds.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if(minTime == time){
                offset1+=1;
            }else{
                minTime = time;
                offset1 = 1;
            }
        }
        String idStr = StrUtil.join(",",blogIds);
        List<Blog> blogList = query().in("id",blogIds).last("ORDER BY FIELD(id,"+idStr+")").list();
        for (Blog blog : blogList) {
            queryBlogUser(blog);
            addIsLiked(blog);
        }
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setOffset(offset1);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    /**
     * 查询帖子作者的信息
     * @param blog
     */
    public void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}















