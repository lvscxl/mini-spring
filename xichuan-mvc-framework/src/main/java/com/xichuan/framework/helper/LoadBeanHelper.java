package com.xichuan.framework.helper;


import com.xichuan.framework.annotation.*;
import com.xichuan.framework.data.BeanDefinition;
import com.xichuan.framework.data.MethodNode;
import com.xichuan.framework.enums.ScopeEnum;
import com.xichuan.framework.interfaces.BeanNameAware;
import com.xichuan.framework.interfaces.BeanPostProcessor;
import com.xichuan.framework.interfaces.InitializingBean;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;


/**
 * @Author Xichuan
 * @Date 2022/5/7 11:25
 * @Description
 */
public class LoadBeanHelper {
    //类加载器
    private static ClassLoader classLoader = Container.classLoader;
    //根路径下的所有类的Class
    private static HashSet<Class<?>> classesHashSet = new HashSet<>();
    //将注册的bean的class封装成BeanDefinition放在此map种
    private static HashMap<String, BeanDefinition> beanDefinitionHashMap = new HashMap<>();
    //切面类中，后置通知方法Map(key:被切面类名词：value:Method(切面类的方法加了@Before; 如果被切面的是方法，被切面的方法名词))
    private static HashMap<String, ArrayList<MethodNode>> beforeDelegatedSet = new HashMap<>();
    //切面类中，前置通知方法Map(key:被切面类名词：value:Method(切面类的方法加了@After; 如果被切面的是方法，被切面的方法名词))
    private static HashMap<String, ArrayList<MethodNode>> afterDelegatedSet = new HashMap<>();
    //PostProcessor后置处理器列表
    private static ArrayList<BeanPostProcessor> beanPostProcessorList = new ArrayList<>();
    private static String splitOP= "\\";

    static {
        if(Utils.getSystem().equals("Linux"))
            splitOP="/";

    }


    /**
     * 将所有的BeanDefinition放入map中，（只有@Component、@Controller、@Service、@Repository才会放入）
     */
    public static void loadAllBean() {
        for (Class<?> clazz : classesHashSet) {
            if (!(clazz.isAnnotationPresent(Component.class)||
                    clazz.isAnnotationPresent(Controller.class)||
                    clazz.isAnnotationPresent(Service.class) ||
                    clazz.isAnnotationPresent(Repository.class)))
                continue;

            //创建BeanDefinition
            BeanDefinition newBeanDefine = new BeanDefinition();
            newBeanDefine.setClazz(clazz);
            //获取beanName
            String BeanName=getBeanNameByClass(clazz);

            if(clazz.isAnnotationPresent(Controller.class))
                newBeanDefine.setIsController(true);
            newBeanDefine.setBeanName(BeanName);



            //加载后置处理器
            //isAssignableFrom()方法是判断是否为某个类的父
            if (BeanPostProcessor.class.isAssignableFrom(clazz)) {
                try {
                    beanPostProcessorList.add((BeanPostProcessor) clazz.getDeclaredConstructor().newInstance());
                } catch (InstantiationException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                } catch (NoSuchMethodException e) {
                    e.printStackTrace();
                }
            }


            //scope作用域注解
            if (clazz.isAnnotationPresent(Scope.class)) {
                String scope = clazz.getDeclaredAnnotation(Scope.class).value();
                newBeanDefine.setScope(scope);
            } else {
                //默认为单例模式
                newBeanDefine.setScope(ScopeEnum.SingleTon.getName());
            }


            //添加切面;(方法上或者类上)
            //判断方法是否有@Aspect切面类注解
            if (clazz.isAnnotationPresent(Aspect.class)) {
                for (Method method : clazz.getMethods()) {
                    String annotationPath = "";

                    //判断哪一个方法是切入点表达式的注解
                    //like this:@PointCut("com.Mytest.service.impl.MethodAspectImpl.methdFunction()")
                    if (method.isAnnotationPresent(PointCut.class)) {
                        String delegateString = method.getAnnotation(PointCut.class).value();
                        annotationPath=delegateString;
                        //表达式是对对方法切面
                        if (delegateString.charAt(delegateString.length() - 1) == ')') {

                            //说明是在某个方法上面的切面
                            annotationPath =annotationPath.replace("()","");
                            //切掉方法保留到类
                            String[] seg=cutName(annotationPath);
                            addToAspects(clazz,seg[0],true,seg[1]);

                        //切面是某个包或者类
                        }else {
                            String annotationPathClone=new String(annotationPath);
                            //like this: file:/D:/development/my_test_code/MySpring/TestProject/target/classes/com/Mytest/service
                            URL resource=Container.classLoader.getResource(annotationPathClone.replace(".","/"));
                            if(resource==null)
                                resource=Container.classLoader.getResource(annotationPathClone.replace(".","/")+".class");
                            File file=new File(resource.getFile());

                            //如果是包名，需要将包下的所有类注册到切面Map中
                            if(file.isDirectory()) {
                                ArrayList<File> fileArray=new ArrayList<>();
                                //获取该路
                                DFSGetCurrentDir(file,fileArray);
                                String key;
                                for(File f:fileArray) {
                                   key = f.getAbsolutePath().replace(splitOP,".");
                                   key = key.substring(key.indexOf(annotationPath),key.indexOf(f.getName())+f.getName().length()-6);
                                    addToAspects(clazz,key,false,"");
                                }

                            //如果是类，直接将类添加进去
                            }else {
                                String key= file.getAbsolutePath().replace(splitOP,".");
                                key=key.substring(key.indexOf(annotationPath),key.indexOf(file.getName())+file.getName().length()-6);
                                addToAspects(clazz,key,false,"");
                            }
                        }

                    }


                }
            }


            //将每一个beanDefinition放在map种
            beanDefinitionHashMap.put(newBeanDefine.getBeanName(), newBeanDefine);

        }
    }

    /**
     * 通过class获取beanName
     * @param clazz
     * @return
     */
    public static String getBeanNameByClass(Class<?> clazz){
        String BeanName=clazz.getName();
        BeanName=getBeanName(BeanName);
        if(clazz.isAnnotationPresent(Component.class)&&!clazz.getAnnotation(Component.class).value().equals("")) {
            BeanName = clazz.getAnnotation(Component.class).value();
        }
        if(clazz.isAnnotationPresent(Controller.class)&&!clazz.getAnnotation(Controller.class).value().equals("")) {
            BeanName = clazz.getAnnotation(Controller.class).value();
        }
        if(clazz.isAnnotationPresent(Service.class)&&!clazz.getAnnotation(Service.class).value().equals("")) {
            BeanName = clazz.getAnnotation(Service.class).value();
        }
        if(clazz.isAnnotationPresent(Repository.class)&&!clazz.getAnnotation(Repository.class).value().equals("")) {
            BeanName = clazz.getAnnotation(Repository.class).value();
        }
        return BeanName;
    }

    /**
     * 添加切入面类
     * @param clazz 切面类Class
     * @param key 被切面的类
     * @param isMethod 是否是对方法切面
     * @param MethodName 被切面的方法名字
     */
    private static void addToAspects(Class<?> clazz,String key,boolean isMethod,String MethodName) {
        for (Method method : clazz.getMethods()) {
            MethodNode methodNode=new MethodNode(method,isMethod);
            methodNode.setMethodName(MethodName);

            if(method.isAnnotationPresent(Before.class)) {
                if(!beforeDelegatedSet.containsKey(key))
                    beforeDelegatedSet.put(key,new ArrayList<>());
               beforeDelegatedSet.get(key).add(methodNode);
            }
            if(method.isAnnotationPresent(After.class)) {
                if(!afterDelegatedSet.containsKey(key))
                    afterDelegatedSet.put(key,new ArrayList<>());
               afterDelegatedSet.get(key).add(methodNode);
            }
        }

    }

    /**
     * 当切点是方法的时候，切割分离方法名称和全限定类名称
     * @param name
     * @return
     */
    private static String[] cutName(String name) {
        StringBuilder res=new StringBuilder("");
        String[] segString=name.split("\\.");
        for(int i=0;i< segString.length-1;i++) {
            res.append(segString[i]);
            if(i<segString.length-2)
                res.append(".");
        }
        return new String[]{res.toString(), segString[segString.length - 1]};

    }

    /**
     * 全包名截取Bean的名称
     * @param FullName
     * @return
     */
    private static String getBeanName(String FullName) {
        String[] name=FullName.split("\\.");
        return name[name.length-1];
    }

    /**
     * 生产单例bean,将需要代理的bean进行代理，放到一级缓存中
     */
    public static void productBean() {
            for (String beanName : beanDefinitionHashMap.keySet()) {
                BeanDefinition beanDefinition = beanDefinitionHashMap.get(beanName);
//                如果是单例变成生产工厂
                if (beanDefinition.getScope().equals(ScopeEnum.SingleTon.getName())) {
                    //创建单例bean
                    createBean(beanDefinition,true);
                }
            }
    }

    /**
     * 创建bean，并进行代理
     * @param beanDefinition bean的定义信息
     * @param singleton 是否是单例bean
     * @return
     */
    private static Object createBean(BeanDefinition beanDefinition, Boolean singleton) {
        try {
            //如果在一级或者二级直接返回;如果是在三级缓存，则将三级缓存中的bean移到二级缓存中
            if(Container.singletonObjects.containsKey(beanDefinition.getBeanName())&&singleton)
                return Container.singletonObjects.get(beanDefinition);
            else if(Container.earlySingletonObjects.containsKey(beanDefinition.getBeanName())) {
                return Container.earlySingletonObjects.get(beanDefinition.getBeanName());
            }else if(Container.singletonFactory.containsKey(beanDefinition.getBeanName())){
                //将此bean放在二级缓存中，并在三级缓存中删除
                Container.earlySingletonObjects.put(beanDefinition.getBeanName(),Container.singletonFactory.get(beanDefinition.getBeanName()).getTarget());
                Container.singletonFactory.remove(beanDefinition.getBeanName());
                return Container.earlySingletonObjects.get(beanDefinition.getBeanName());

            //此bean不存在，或者在二级缓存中时的逻辑代码
            }else {
                //如果该类是接口，直接返回null
                if(beanDefinition.getClazz().isInterface())
                   return null;

                //创建bean对象
                Object instance = beanDefinition.getClazz().getDeclaredConstructor().newInstance();

                //将bean对象放到动态代理工厂中
                DynamicBeanFactory dynamicBeanFactory = new DynamicBeanFactory();
                dynamicBeanFactory.setInstance(instance);
                dynamicBeanFactory.setBeanDefinition(beanDefinition);
                //查看是否存在切面并放入工厂中，在工厂中准备代理
                //如果类使用了aop，需要进行动态代理处理
                if(beforeDelegatedSet.containsKey(beanDefinition.getClazz().getName())) {
                    dynamicBeanFactory.setDelegated(true);
                    dynamicBeanFactory.setBeforeMethodCache(beforeDelegatedSet.get(beanDefinition.getClazz().getName()));
                }
                if(afterDelegatedSet.containsKey(beanDefinition.getClazz().getName())) {
                    dynamicBeanFactory.setDelegated(true);
                    dynamicBeanFactory.setAfterMethodCache(afterDelegatedSet.get(beanDefinition.getClazz().getName()));
                }

                //扔到三级缓存
                Container.singletonFactory.put(beanDefinition.getBeanName(), dynamicBeanFactory);
                //TODO 此处代码非常重要，此todo是为了方便找到代码
                //将此bean上的@Autowired注解的类都进行注入
                Object targetBean = populate(beanDefinition.getBeanName());

                //将对象从三级缓存与二级缓存中清除
                if(Container.earlySingletonObjects.containsKey(beanDefinition.getBeanName()))
                    Container.earlySingletonObjects.remove(beanDefinition.getBeanName());
                if(Container.singletonFactory.containsKey(beanDefinition.getBeanName()))
                    Container.singletonFactory.remove(beanDefinition.getBeanName());
                //将bean对象存放到一级缓存中
                Container.singletonObjects.put(beanDefinition.getBeanName(),targetBean);


                //加入ControllerMap引用
                if(beanDefinition.isController())
                    Container.controllerMap.put(beanDefinition.getBeanName(),Container.singletonObjects.get(beanDefinition.getBeanName()));

                //处理BeanNameAware的setBeanName
                if(targetBean instanceof BeanNameAware) {
                    ((BeanNameAware)targetBean).setBeanName(beanDefinition.getBeanName());
                }

                //Spring容器中完成bean实例化、配置以及其他初始化方法前添加一些自己逻辑处理
                for(BeanPostProcessor processor:beanPostProcessorList) {
                    Container.singletonObjects.put(beanDefinition.getBeanName(),processor.postProcessBeforeInitialization(targetBean, beanDefinition.getBeanName()));
                }

                //InitializingBean接口为bean提供了初始化方法的方式，它只包括afterPropertiesSet方法，凡是继承该接口的类，在初始化bean的时候会执行该方法。
                if(targetBean instanceof InitializingBean) {
                   ((InitializingBean) targetBean).afterPropertiesSet();
                }

                //Spring容器中完成bean实例化、配置以及其他初始化方法后添加一些自己逻辑处理
                for(BeanPostProcessor processor:beanPostProcessorList) {
                    Container.singletonObjects.put(beanDefinition.getBeanName(),processor.postProcessAfterInitialization(targetBean, beanDefinition.getBeanName()));
                }
            }

        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }

        Object rs = Container.singletonObjects.get(beanDefinition.getBeanName());
        //如果不是单例，要将上面处理的一级缓存中的单例bean清除，并返回bean对象
        if(!singleton) {
            //不是单例删除
            Container.singletonObjects.remove(beanDefinition.getBeanName());
            return rs;
        }
        return rs;
    }


    /**
     * 获取属性，填充属性；对@Autowired的处理
     * @param beanName
     * @return
     */
    private static Object populate(String beanName) {
        try {
            //获取bean对象
            Class<?> bean=null;
            if(Container.singletonFactory.containsKey(beanName))
                bean=Container.singletonFactory.get(beanName).getInstance().getClass();
            else if(Container.earlySingletonObjects.containsKey(beanName))
                bean=Container.earlySingletonObjects.get(beanName).getClass();


            //遍历bean的方法
            for (Field declaredField : bean.getDeclaredFields()) {
                if(!declaredField.isAnnotationPresent(Autowired.class))
                    continue;

                String methodBenName = null;
                //如果此类是接口的话，获取此方法的实现类；如果没有实现类，则获取类本身
                Class<?> implementClass = findImplementClass(declaredField.getType());
                //如果实现类为null，那么就用本身
                if (implementClass == null){
                    implementClass = declaredField.getDeclaringClass();
                }
                //通过class获取beanName
                methodBenName = getBeanNameByClass(implementClass);

/*                if(!Utils.isNotBlack(methodBenName)) {
                    //如果此方法上有@Autowired注解，获取beanName
                    //对com.xx.xx.XXService进行切割
                    String[] name = declaredField.getType().toString().split("\\.");
                    //最后一个字符串就是beanName
                    methodBenName = name[name.length - 1];
                }*/

                //如果@Autowired的value不为"",那么beanName就是value的值
                if(!declaredField.getAnnotation(Autowired.class).value().equals(""))
                    methodBenName=declaredField.getAnnotation(Autowired.class).value();

                //获取此方法上的bean
                Object methodBean = getBean(methodBenName);
                declaredField.setAccessible(true);

                //重新设置该方法属性值（即：对接口注入子类对象）
                //declaredField.set(bean,methodBean);
                if(Container.singletonFactory.containsKey(beanName)) {
                    //Field.set(该Field所属的类对象，该对象的新值)
                    declaredField.set(Container.singletonFactory.get(beanName).getInstance(), methodBean);
                } else if(Container.earlySingletonObjects.containsKey(beanName))
                    declaredField.set(Container.earlySingletonObjects.get(beanName),methodBean);
            }

            //返回此类的bean
            if(Container.singletonFactory.containsKey(beanName)) {
                Object res = Container.singletonFactory.get(beanName).getTarget();
                return res;
            } else if(Container.earlySingletonObjects.containsKey(beanName)) {
                Object res = Container.earlySingletonObjects.get(beanName);
                return  res;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 获取接口对应的实现类
     */
    public static Class<?> findImplementClass(Class<?> interfaceClass) {
        Class<?> implementClass = interfaceClass;
        //接口对应的所有实现类
        Set<Class<?>> classSetBySuper = getClassSetBySuper(interfaceClass);
        if (Utils.isNotEmpty(classSetBySuper)) {
            //获取第一个实现类
            implementClass = classSetBySuper.iterator().next();
        }
        return implementClass;
    }

    /**
     * 获取基础包名下某父类的所有子类 或某接口的所有实现类
     */
    public static Set<Class<?>> getClassSetBySuper(Class<?> superClass) {
        Set<Class<?>> classSet = new HashSet<Class<?>>();
        for (Class<?> cls : classesHashSet) {
            //isAssignableFrom() 指 superClass 和 cls 是否相同或 superClass 是否是 cls 的父类/接口
            if (superClass.isAssignableFrom(cls) && !superClass.equals(cls)) {
                classSet.add(cls);
            }
        }
        return classSet;
    }

    /**
     * 通过beanName获取bean（如果是单例，且一级缓存中不存在，就创建bean）
     * @param beanName
     * @return
     */
    public static Object getBean(String beanName) {
        //如果是单例，则从一级缓存中获取，如果获取不到，则创建bean并存放到一级缓存中
        if(beanDefinitionHashMap.get(beanName).getScope().equals(ScopeEnum.SingleTon.getName())) {
            if (Container.singletonObjects.containsKey(beanName)) {
                return Container.singletonObjects.get(beanName);
            }
            else {
                return createBean(beanDefinitionHashMap.get(beanName),true);
            }
        //如果不是单例，则创建bean,且不将此bean存放到一级缓存中
        }else {
            return createBean(beanDefinitionHashMap.get(beanName),false);
        }
    }






    /**
     *获取packagePath下的所有class
     *
     * @param packagePath 扫描路径
     * @return 所有类的class对象
     */
    public static Set<Class<?>> loadAllClass(String packagePath) {
        URL resource=classLoader.getResource(packagePath.replace(".","/"));
        ArrayList<File> files=new ArrayList<>();
        DFSGetCurrentDir(new File(resource.getFile()),files);
        Class<?>clazz;
        for (File file:files) {
            String fileName= file.getAbsolutePath();
            //+8是为了跳过classes这个字符串
            String className=fileName.substring(fileName.indexOf("classes")+8,fileName.indexOf(".class")).replace(splitOP,".");
            try {
                clazz=classLoader.loadClass(className);
                classesHashSet.add(clazz);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
        return classesHashSet;
    }

    /**
     * 递归遍历所有的文件夹
     * @param file
     * @param fileArray
     */

    private static void DFSGetCurrentDir(File file, ArrayList<File> fileArray) {
        File[] name=file.listFiles();
        if(name.length==0)
            return ;
        for(File every:name) {
            if(every.isFile()) { //是文件
                fileArray.add(every);
            }else {
                //是目录
                DFSGetCurrentDir(every,fileArray);
            }
        }
    }



}