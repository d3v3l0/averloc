import os
import argparse
import logging
import time
import csv

import torch
from torch.optim.lr_scheduler import StepLR
import torchtext

import seq2seq
from seq2seq.trainer import SupervisedAdversarialTrainer
from seq2seq.models import EncoderRNN, DecoderRNN, Seq2seq
from seq2seq.loss import Perplexity
from seq2seq.optim import Optimizer
from seq2seq.dataset import SourceField, TargetField
from seq2seq.evaluator import Predictor
from seq2seq.util.checkpoint import Checkpoint

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument('--train_path', action='store', dest='train_path',
                        help='Path to train data')
    parser.add_argument('--dev_path', action='store', dest='dev_path',
                        help='Path to dev data')
    parser.add_argument('--expt_dir', action='store', dest='expt_dir', default='./experiment',
                        help='Path to experiment directory. If load_checkpoint is True, then path to checkpoint directory has to be provided')
    parser.add_argument('--load_checkpoint', action='store', dest='load_checkpoint',
                        help='The name of the checkpoint to load, usually an encoded time string', default=None)
    parser.add_argument('--resume', action='store_true', dest='resume',
                        default=False,
                        help='Indicates if training has to be resumed from the latest checkpoint')
    parser.add_argument('--log-level', dest='log_level',
                        default='info',
                        help='Logging level.')
    parser.add_argument('--expt_name', action='store', dest='expt_name',default=None)
    parser.add_argument('--regular_training', action='store_true', default=False)

    opt = parser.parse_args()
    return opt


def load_data(data_path, fields=(SourceField(), SourceField(), TargetField()), filter_func=lambda x: True):
    src, src_adv, tgt = fields

    fields_inp = []
    with open(data_path, 'r') as f:
        cols = f.readline()[:-1].split('\t')
        for col in cols:
            if col=='src':
                fields_inp.append(('src', src))
            elif col=='tgt':
                fields_inp.append(('tgt', tgt))
            else:
                fields_inp.append((col, src_adv))

    data = torchtext.data.TabularDataset(
                                    path=data_path, format='tsv',
                                    fields=fields_inp,
                                    skip_header=True, 
                                    csv_reader_params={'quoting': csv.QUOTE_NONE}, 
                                    filter_pred=filter_func
                                    )

    return data, fields_inp, src, src_adv, tgt



opt = parse_args()

if not opt.resume:
    expt_name = time.strftime('%Y_%m_%d_%H_%M_%S', time.localtime()) if opt.expt_name is None else opt.expt_name
    opt.expt_dir = os.path.join(opt.expt_dir, expt_name)
    if not os.path.exists(opt.expt_dir):
        os.makedirs(opt.expt_dir)

print('Folder name:', opt.expt_dir)

LOG_FORMAT = '%(asctime)s %(name)-12s %(levelname)-8s %(message)s'
logging.basicConfig(format=LOG_FORMAT, level=getattr(logging, opt.log_level.upper()), 
                                        filename=os.path.join(opt.expt_dir, 'experiment.log'), filemode='a')

logging.info(vars(opt))

params = {
            'n_layers': 2,
            'hidden_size': 512, 
            'src_vocab_size': 50000, 
            'tgt_vocab_size': 50000, 
            'max_len': 128, 
            'rnn_cell':'lstm',
            'batch_size': 128, 
            'num_epochs': 15
        }

logging.info(params)

max_len = params['max_len']
def len_filter(example):
    return len(example.src) <= max_len and len(example.tgt) <= max_len

train, fields, src, src_adv, tgt = load_data(opt.train_path, filter_func=len_filter)
dev, fields, src, src_adv, tgt = load_data(opt.dev_path, fields=(src, src_adv, tgt), filter_func=len_filter)

attacks = [field[0] for field in fields if field[0] not in ['tgt']]
if opt.regular_training:
    attacks = []

logging.info('Loaded data')

logging.info(('Size of train: %d, Size of validation: %d' %(len(train), len(dev))))

logging.info(('Attacks:%s'%(str(attacks))))

if opt.resume:
    if opt.load_checkpoint is None:
        raise Exception('load_checkpoint must be specified when --resume is specified')
    else:
        logging.info("loading checkpoint from {}".format(os.path.join(opt.expt_dir, Checkpoint.CHECKPOINT_DIR_NAME, opt.load_checkpoint)))
        checkpoint_path = os.path.join(opt.expt_dir, Checkpoint.CHECKPOINT_DIR_NAME, opt.load_checkpoint)
        checkpoint = Checkpoint.load(checkpoint_path)
        seq2seq = checkpoint.model
        src.vocab = checkpoint.input_vocab
        tgt.vocab = checkpoint.output_vocab
else:
    src.build_vocab(train, max_size=params['src_vocab_size'])
    tgt.build_vocab(train, max_size=params['tgt_vocab_size'])
    # input_vocab = src.vocab
    # output_vocab = tgt.vocab   

src_adv.vocab = src.vocab 

# Prepare loss
weight = torch.ones(len(tgt.vocab))
pad = tgt.vocab.stoi[tgt.pad_token]
loss = Perplexity(weight, pad)
if torch.cuda.is_available():
    loss.cuda()

batch_adv_loss = Perplexity(weight, pad)
if torch.cuda.is_available():
    batch_adv_loss.cuda()


# seq2seq = None
optimizer = None
if not opt.resume:
    # Initialize model
    hidden_size=params['hidden_size']
    bidirectional = True
    encoder = EncoderRNN(len(src.vocab), max_len, hidden_size,
                         bidirectional=bidirectional, variable_lengths=True, 
                         n_layers=params['n_layers'], rnn_cell=params['rnn_cell'])
    decoder = DecoderRNN(len(tgt.vocab), max_len, hidden_size * 2 if bidirectional else hidden_size,
                         dropout_p=0.2, use_attention=True, bidirectional=bidirectional, 
                         rnn_cell=params['rnn_cell'], n_layers=params['n_layers'], 
                         eos_id=tgt.eos_id, sos_id=tgt.sos_id)
    seq2seq = Seq2seq(encoder, decoder)
    if torch.cuda.is_available():
        seq2seq.cuda()

    for param in seq2seq.parameters():
        param.data.uniform_(-0.08, 0.08)

    # Optimizer and learning rate scheduler can be customized by
    # explicitly constructing the objects and pass to the trainer.
    #
    optimizer = Optimizer(torch.optim.Adam(seq2seq.parameters()), max_grad_norm=5)
    scheduler = StepLR(optimizer.optimizer, 1)
    optimizer.set_scheduler(scheduler)

logging.info(seq2seq)

# train 
t = SupervisedAdversarialTrainer(loss=loss, batch_size=params['batch_size'],
                      checkpoint_every=50,
                      print_every=100, expt_dir=opt.expt_dir, tensorboard=True, 
                      batch_adv_loss=batch_adv_loss)

seq2seq = t.train(seq2seq, train,
                  num_epochs=params['num_epochs'], dev_data=dev,
                  optimizer=optimizer,
                  teacher_forcing_ratio=0.5,
                  resume=opt.resume, 
                  load_checkpoint=opt.load_checkpoint, 
                  attacks=attacks)