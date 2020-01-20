# Results on `java-small` (01-16-2020)

## Config

Use `500` top target sub-tokens as pool for random name building.

Used the follow transformers config:
```java
transformers.add(new InsertPrintStatements(
  3,                 // Min insertions
  9,                 // Max insertions
  3,                 // Min literal length
  10,                // Max literal length
  topTargetSubtokens // Subtokens to use to build literal
));

transformers.add(new RenameFields(
  2,                 // Name min length
  10,                // Name max length
  1.0,               // Percent to rename
  topTargetSubtokens // Subtokens to use to build names
));

transformers.add(new RenameLocalVariables(
  2,                 // Name min length
  10,                // Name max length
  1.0,               // Percent to rename
  topTargetSubtokens // Subtokens to use to build names
));

transformers.add(new RenameParameters(
  2,                 // Name min length
  10,                // Name max length
  1.0,               // Percent to rename
  topTargetSubtokens // Subtokens to use to build names
));

transformers.add(new ReplaceTrueFalse(
  1.0 // Replacement chance
));

// Use all the previous in our "All" transformer
transformers.add(new All(
  (ArrayList<AverlocTransformer>)transformers.clone()
));

transformers.add(new ShuffleLocalVariables(
  1.0 // Percentage to shuffle
));

transformers.add(new ShuffleParameters(
  1.0 // Percentage to shuffle
));

transformers.add(new Identity(
  // No params
));
```

## Metrics

```
Type , Transform Name                   , F1    , Precision, Recall, Accuracy, Rouge-1 F1, Rouge-2 F1, Rouge-L F1
(BSE), transforms.All                   ,  0.617,     0.674,  0.568,    0.317,      0.610,      0.318,      0.576
(TRN), transforms.All                   ,  0.277,     0.313,  0.248,    0.085,      0.263,      0.080,      0.241
(REL), transforms.All                   , -0.340,    -0.361, -0.320,   -0.231,     -0.347,     -0.238,     -0.334
(TRN), transforms.Identity              ,  0.612,     0.670,  0.563,    0.312,      0.605,      0.312,      0.571
(BSE), transforms.InsertPrintStatements ,  0.611,     0.669,  0.562,    0.312,      0.604,      0.312,      0.570
(TRN), transforms.InsertPrintStatements ,  0.352,     0.420,  0.304,    0.128,      0.339,      0.118,      0.312
(REL), transforms.InsertPrintStatements , -0.259,    -0.249, -0.259,   -0.184,     -0.265,     -0.194,     -0.259
(BSE), transforms.RenameFields          ,  0.619,     0.678,  0.570,    0.327,      0.610,      0.330,      0.575
(TRN), transforms.RenameFields          ,  0.438,     0.442,  0.435,    0.189,      0.450,      0.183,      0.420
(REL), transforms.RenameFields          , -0.181,    -0.236, -0.135,   -0.138,     -0.160,     -0.146,     -0.155
(BSE), transforms.RenameLocalVariables  ,  0.530,     0.597,  0.476,    0.206,      0.525,      0.222,      0.489
(TRN), transforms.RenameLocalVariables  ,  0.380,     0.391,  0.369,    0.141,      0.394,      0.139,      0.367
(REL), transforms.RenameLocalVariables  , -0.150,    -0.206, -0.107,   -0.065,     -0.130,     -0.083,     -0.121
(BSE), transforms.RenameParameters      ,  0.599,     0.640,  0.563,    0.329,      0.593,      0.292,      0.562
(TRN), transforms.RenameParameters      ,  0.407,     0.391,  0.423,    0.177,      0.411,      0.175,      0.385
(REL), transforms.RenameParameters      , -0.193,    -0.249, -0.140,   -0.152,     -0.181,     -0.117,     -0.178
(BSE), transforms.ReplaceTrueFalse      ,  0.521,     0.610,  0.455,    0.223,      0.525,      0.219,      0.488
(TRN), transforms.ReplaceTrueFalse      ,  0.511,     0.601,  0.444,    0.216,      0.513,      0.210,      0.476
(REL), transforms.ReplaceTrueFalse      , -0.010,    -0.009, -0.010,   -0.007,     -0.012,     -0.009,     -0.012
(BSE), transforms.ShuffleLocalVariables ,  0.499,     0.572,  0.443,    0.163,      0.492,      0.185,      0.456
(TRN), transforms.ShuffleLocalVariables ,  0.484,     0.554,  0.429,    0.154,      0.477,      0.174,      0.442
(REL), transforms.ShuffleLocalVariables , -0.015,    -0.018, -0.014,   -0.010,     -0.015,     -0.011,     -0.014
(BSE), transforms.ShuffleParameters     ,  0.539,     0.586,  0.499,    0.270,      0.532,      0.244,      0.503
(TRN), transforms.ShuffleParameters     ,  0.535,     0.583,  0.495,    0.268,      0.529,      0.242,      0.499
(REL), transforms.ShuffleParameters     , -0.003,    -0.003, -0.003,   -0.003,     -0.004,     -0.002,     -0.004

```